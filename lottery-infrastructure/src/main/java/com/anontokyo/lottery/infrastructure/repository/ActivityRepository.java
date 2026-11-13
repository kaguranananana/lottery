package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.aggregates.ActivityInfoLimitPageRich;
import com.anontokyo.lottery.domain.activity.model.req.ActivityInfoLimitPageReq;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.vo.*;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.infrastructure.cache.CacheConfigProperties;
import com.anontokyo.lottery.infrastructure.cache.TwoLevelCacheService;
import com.anontokyo.lottery.infrastructure.dao.*;
import com.anontokyo.lottery.infrastructure.po.ActivityPrize;
import com.anontokyo.lottery.infrastructure.po.*;
import com.anontokyo.lottery.infrastructure.utils.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */
@Repository
public class ActivityRepository implements IActivityRepository {

    private Logger logger = LoggerFactory.getLogger(ActivityRepository.class);

    @Resource
    private IActivityDao activityDao;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private IAwardDao awardDao;

    @Resource
    private IActivityPrizeDao activityPrizeDao;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private TwoLevelCacheService twoLevelCacheService;

    @Resource
    private CacheConfigProperties cacheConfigProperties;

    @Override
    public void addActivity(ActivityVO activity) {
        Activity req = new Activity();
        BeanUtils.copyProperties(activity, req);
        activityDao.insert(req);
        twoLevelCacheService.evictActivityCache(Constants.RedisKey.KEY_LOTTERY_CACHE_ACTIVITY(activity.getActivityId()));
    }

    @Override
    public void addAward(List<AwardVO> awardList) {
        List<Award> req = new ArrayList<>();
        for (AwardVO awardVO : awardList) {
            Award award = new Award();
            BeanUtils.copyProperties(awardVO, award);
            req.add(award);
        }
        awardDao.insertList(req);
    }

    @Override
    public void addActivityPrizeList(List<ActivityPrizeVO> activityPrizeList) {
        List<ActivityPrize> req = new ArrayList<>();
        Set<Long> activityIds = new HashSet<>();
        for (ActivityPrizeVO activityPrizeVO : activityPrizeList) {
            ActivityPrize activityPrize = new ActivityPrize();
            BeanUtils.copyProperties(activityPrizeVO, activityPrize);
            req.add(activityPrize);
            activityIds.add(activityPrizeVO.getActivityId());
        }
        activityPrizeDao.insertList(req);
        for (Long activityId : activityIds) {
            twoLevelCacheService.evictActivityPrizeSnapshotCache(
                    Constants.RedisKey.KEY_LOTTERY_CACHE_ACTIVITY_PRIZE_SNAPSHOT(activityId));
        }

    }

    @Override
    public boolean alterStatus(Long activityId, Constants.ActivityState beforeState,
            Constants.ActivityState afterState) {
        if (afterState == Constants.ActivityState.RUNNING) {
            // 所有启动入口集中在仓储，数据库行锁串行化预热与状态提交。
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                Activity activity = activityDao.queryActivityForUpdate(activityId);
                // 重复启动或已关闭不再预热，避免运行期缺失库存被回填。
                if (activity == null || beforeState != Constants.ActivityState.READY
                        || !beforeState.getCode().equals(activity.getState())) {
                    return false;
                }
                Date now = new Date();
                // 以锁定的真实活动时间为准，尚未开始或已经结束均不能启动。
                if (activity.getBeginDateTime().after(now) || !activity.getEndDateTime().after(now)) {
                    return false;
                }
                // 任意 Redis 写入失败必须抛出，事务回滚使状态保持 READY。
                warmupAwardStock(activity.getActivityId());
                // 奖品库存可用后才发布 RUNNING，后续抽奖才能看到运行状态。
                return updateStatus(activityId, beforeState, afterState);
            }));
        }
        return updateStatus(activityId, beforeState, afterState);
    }

    private boolean updateStatus(Long activityId, Constants.ActivityState beforeState,
            Constants.ActivityState afterState) {
        AlterStateVO alterStateVO = new AlterStateVO(
                activityId,
                beforeState.getCode(),
                afterState.getCode());
        int count = activityDao.alterState(alterStateVO);
        if (count == 1) {
            twoLevelCacheService.evictActivityCache(Constants.RedisKey.KEY_LOTTERY_CACHE_ACTIVITY(activityId));
        }
        return count == 1;
    }
    @Override
    public ActivityBillVO queryActivityBill(PartakeReq req) {
        Activity activity = twoLevelCacheService.getRedisCache(
                Constants.RedisKey.KEY_LOTTERY_CACHE_ACTIVITY(req.getActivityId()),
                Activity.class,
                cacheConfigProperties.getActivity(),
                () -> activityDao.queryActivityById(req.getActivityId()));

        ActivityBillVO activityBillVO = new ActivityBillVO();
        activityBillVO.setuId(req.getuId());
        activityBillVO.setActivityId(req.getActivityId());

        activityBillVO.setBeginDateTime(activity.getBeginDateTime());
        activityBillVO.setEndDateTime(activity.getEndDateTime());
        activityBillVO.setTakeCount(activity.getTakeCount());
        activityBillVO.setState(activity.getState());
        return activityBillVO;
    }

    @Override
    public List<ActivityVO> scanToDoActivityList(Long id) {
        List<Activity> activityList = activityDao.scanToDoActivityList(id);
        List<ActivityVO> activityVOList = new ArrayList<>(activityList.size());
        for (Activity activity : activityList) {
            ActivityVO activityVO = new ActivityVO();
            activityVO.setId(activity.getId());
            activityVO.setActivityId(activity.getActivityId());
            activityVO.setActivityName(activity.getActivityName());
            activityVO.setBeginDateTime(activity.getBeginDateTime());
            activityVO.setEndDateTime(activity.getEndDateTime());
            activityVO.setState(activity.getState());
            activityVO.setCreator(activity.getCreator());
            activityVOList.add(activityVO);
        }
        return activityVOList;
    }

    /** 仅允许 READY 活动预热奖品；奖池投入使用后，缺失库存必须先对账。 */
    @Override
    public int warmupAwardStockForActivity(Long activityId) {
        return transactionTemplate.execute(status -> {
            // 与启动使用同一行锁，保证检查 READY 到最后一个 SETNX 期间不会启动。
            Activity activity = activityDao.queryActivityForUpdate(activityId);
            if (activity == null || !Constants.ActivityState.READY.getCode().equals(activity.getState())) {
                throw new IllegalStateException("只有 READY 活动允许预热，activityId=" + activityId);
            }
            return warmupAwardStock(activity.getActivityId());
        });
    }

    private int warmupAwardStock(Long activityId) {
        // 调用方持有活动行锁并已确认 READY；RUNNING 和 CLOSED 永远不从 MySQL 回填。
        List<ActivityPrize> prizes = activityPrizeDao.queryActivityPrizeList(activityId);
        if (prizes.isEmpty()) {
            throw new IllegalStateException("活动奖池不能为空，activityId=" + activityId);
        }
        int warmedKeys = 0;
        for (ActivityPrize prize : prizes) {
            // SETNX 保留已预热余额，只补齐首次启动前未建立的 Key。
            String key = Constants.RedisKey.KEY_LOTTERY_ACTIVITY_PRIZE_STOCK_COUNT(activityId, prize.getAwardId());
            warmedKeys += warmupKey(key, prize.getAwardSurplusCount());
        }
        logger.info("award stock warmup finished activityId:{} keysWarmup:{}", activityId, warmedKeys);
        return warmedKeys;
    }

    private int warmupKey(String key, Integer initialValue) {
        try {
            // SETNX 保留之前成功预热的库存，绝不以数据库值覆盖实时库存。
            return redisUtil.setIfAbsent(key, String.valueOf(initialValue)) ? 1 : 0;
        } catch (RuntimeException e) {
            // 保留出错 Key 和堆栈，异常向上传播使启动事务失败。
            logger.error("warmup stock key failed, key:{}", key, e);
            throw e;
        }
    }

    @Override
    public ActivityInfoLimitPageRich queryActivityInfoLimitPage(ActivityInfoLimitPageReq req) {
        Long count = activityDao.queryActivityInfoCount(req);
        List<Activity> list = activityDao.queryActivityInfoList(req);

        List<ActivityVO> activityVOList = new ArrayList<>();
        for (Activity activity : list) {
            ActivityVO activityVO = new ActivityVO();
            BeanUtils.copyProperties(activity, activityVO);
            activityVOList.add(activityVO);
        }

        return new ActivityInfoLimitPageRich(count, activityVOList);
    }

    @Override
    public List<ActivityVO> queryRunningActivityList() {
        List<Activity> activityList = activityDao.queryRunningActivityList();
        List<ActivityVO> activityVOList = new ArrayList<>();
        for (Activity activity : activityList) {
            ActivityVO activityVO = new ActivityVO();
            activityVO.setActivityId(activity.getActivityId());
            activityVO.setActivityName(activity.getActivityName());
            activityVO.setActivityDesc(activity.getActivityDesc());
            activityVO.setBeginDateTime(activity.getBeginDateTime());
            activityVO.setEndDateTime(activity.getEndDateTime());
            activityVO.setState(activity.getState());
            activityVOList.add(activityVO);
        }
        return activityVOList;
    }
}
