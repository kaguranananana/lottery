package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.aggregates.ActivityInfoLimitPageRich;
import com.anontokyo.lottery.domain.activity.model.req.ActivityInfoLimitPageReq;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.StockResult;
import com.anontokyo.lottery.domain.activity.model.vo.*;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.infrastructure.cache.CacheConfigProperties;
import com.anontokyo.lottery.infrastructure.cache.TwoLevelCacheService;
import com.anontokyo.lottery.infrastructure.dao.*;
import com.anontokyo.lottery.infrastructure.po.StrategyDetail;
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
    private IStrategyDao strategyDao;

    @Resource
    private IStrategyDetailDao strategyDetailDao;

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
        // 新建活动尚未发生参与，剩余库存必须从总库存初始化。
        req.setStockSurplusCount(activity.getStockCount());
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
        for (AwardVO awardVO : awardList) {
        }
    }

    @Override
    public void addStrategy(StrategyVO strategy) {
        Strategy req = new Strategy();
        BeanUtils.copyProperties(strategy, req);
        strategyDao.insert(req);
        twoLevelCacheService.evictStrategySnapshotCache(
                Constants.RedisKey.KEY_LOTTERY_CACHE_STRATEGY_SNAPSHOT(strategy.getStrategyId()));
    }

    @Override
    public void addStrategyDetailList(List<StrategyDetailVO> strategyDetailList) {
        List<StrategyDetail> req = new ArrayList<>();
        Set<Long> strategyIds = new HashSet<>();
        for (StrategyDetailVO strategyDetailVO : strategyDetailList) {
            StrategyDetail strategyDetail = new StrategyDetail();
            BeanUtils.copyProperties(strategyDetailVO, strategyDetail);
            req.add(strategyDetail);
            strategyIds.add(strategyDetailVO.getStrategyId());
        }
        strategyDetailDao.insertList(req);
        for (Long strategyId : strategyIds) {
            twoLevelCacheService.evictStrategySnapshotCache(
                    Constants.RedisKey.KEY_LOTTERY_CACHE_STRATEGY_SNAPSHOT(strategyId));
        }

    }

    @Override
    public boolean alterStatus(Long activityId, Constants.ActivityState beforeState,
            Constants.ActivityState afterState) {
        if (afterState == Constants.ActivityState.DOING) {
            // 所有启动入口集中在仓储，数据库行锁串行化预热与状态提交。
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                Activity activity = activityDao.queryActivityForUpdate(activityId);
                // 重复启动或已关闭不再预热，避免运行期缺失库存被回填。
                if (activity == null || beforeState != Constants.ActivityState.PASS
                        || !beforeState.getCode().equals(activity.getState())) {
                    return false;
                }
                Date now = new Date();
                // 以锁定的真实活动时间为准，尚未开始或已经结束均不能启动。
                if (activity.getBeginDateTime().after(now) || !activity.getEndDateTime().after(now)) {
                    return false;
                }
                // 任意 Redis 写入失败必须抛出，事务回滚使状态保持 PASS。
                warmupStock(activity);
                // 全部库存 Key 已初始化后才发布 DOING，后续抽奖才能看到运行状态。
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
        activityBillVO.setActivityName(activity.getActivityName());
        activityBillVO.setBeginDateTime(activity.getBeginDateTime());
        activityBillVO.setEndDateTime(activity.getEndDateTime());
        activityBillVO.setTakeCount(activity.getTakeCount());
        activityBillVO.setStockCount(activity.getStockCount());
        activityBillVO.setStockSurplusCount(activity.getStockSurplusCount());
        activityBillVO.setStrategyId(activity.getStrategyId());
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

    /**
     * Redis 原子预扣活动库存。RUNNING 期间 key 不存在属于异常状态：
     * 绝不能用可能滞后的 MySQL 值回填（会造成库存复活），必须 fail closed 由人工/对账恢复。
     */
    @Override
    public StockResult substractionActivityStockByRedis(String uId, Long activityId) {
        String stockKey = Constants.RedisKey.KEY_LOTTERY_ACTIVITY_STOCK_COUNT(activityId);
        try {
            String cacheValue = redisUtil.get(stockKey);
            if (cacheValue == null || cacheValue.length() == 0) {
                logger.error("activity stock key missing on running activity, fail closed. "
                        + "run stock warmup/recovery manually, activityId:{} key:{}", activityId, stockKey);
                return new StockResult(Constants.ResponseCode.STOCK_STATE_ERROR.getCode(),
                        Constants.ResponseCode.STOCK_STATE_ERROR.getInfo());
            }
            long current = Long.parseLong(cacheValue);
            if (current <= 0) {
                return new StockResult(Constants.ResponseCode.OUT_OF_STOCK.getCode(),
                        Constants.ResponseCode.OUT_OF_STOCK.getInfo());
            }

            long stockSurplusCount = redisUtil.decr(stockKey, 1);
            if (stockSurplusCount < 0) {
                redisUtil.incr(stockKey, 1);
                return new StockResult(Constants.ResponseCode.OUT_OF_STOCK.getCode(),
                        Constants.ResponseCode.OUT_OF_STOCK.getInfo());
            }
            return new StockResult(Constants.ResponseCode.SUCCESS.getCode(), Constants.ResponseCode.SUCCESS.getInfo(),
                    (int) stockSurplusCount);
        } catch (Exception e) {
            // fail closed：无法确认 Redis 库存状态时不允许继续抽奖
            logger.error("activity stock redis operation failed, fail closed. activityId:{} key:{}",
                    activityId, stockKey, e);
            return new StockResult(Constants.ResponseCode.STOCK_STATE_ERROR.getCode(),
                    Constants.ResponseCode.STOCK_STATE_ERROR.getInfo());
        }
    }

    @Override
    public void recoverActivityStockByRedis(Long activityId) {
        String stockKey = Constants.RedisKey.KEY_LOTTERY_ACTIVITY_STOCK_COUNT(activityId);
        try {
            long stockSurplusCount = redisUtil.incr(stockKey, 1);
            if (stockSurplusCount < 0) {
                redisUtil.decr(stockKey, 1);
                logger.warn("recover activity stock by redis got negative value, activityId:{} key:{}", activityId,
                        stockKey);
            }
        } catch (Exception e) {
            logger.error("recover activity stock by redis failed, activityId:{} key:{}", activityId, stockKey, e);
        }
    }

    /** 仅允许未启动 PASS 活动预热；部分成功保留，下轮 SETNX 补齐，禁止运行期回填。 */
    @Override
    public int warmupStockForActivity(Long activityId) {
        return transactionTemplate.execute(status -> {
            // 与启动使用同一行锁，保证检查 PASS 到最后一个 SETNX 期间不会启动。
            Activity activity = activityDao.queryActivityForUpdate(activityId);
            if (activity == null || !Constants.ActivityState.PASS.getCode().equals(activity.getState())) {
                throw new IllegalStateException("只有 PASS 活动允许预热，activityId=" + activityId);
            }
            return warmupStock(activity);
        });
    }

    private int warmupStock(Activity activity) {
        if (activity.getStrategyId() == null || activity.getStockSurplusCount() == null) {
            throw new IllegalStateException("活动策略或库存未初始化，activityId=" + activity.getActivityId());
        }
        List<StrategyDetail> details = strategyDetailDao.queryStrategyDetailList(activity.getStrategyId());
        if (details.isEmpty()) {
            throw new IllegalStateException("活动策略没有奖品，strategyId=" + activity.getStrategyId());
        }
        // 初始化活动 Key；已经存在的库存不覆盖，即使它已小于数据库值。
        int warmedKeys = warmupKey(Constants.RedisKey.KEY_LOTTERY_ACTIVITY_STOCK_COUNT(activity.getActivityId()),
                activity.getStockSurplusCount());
        // 逐个初始化奖品 Key；任何一步失败都会阻止活动进入 DOING。
        for (StrategyDetail detail : details) {
            warmedKeys += warmupKey(Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_COUNT(
                    detail.getStrategyId(), detail.getAwardId()), detail.getAwardSurplusCount());
        }
        logger.info("stock warmup finished activityId:{} keysWarmup:{}", activity.getActivityId(), warmedKeys);
        return warmedKeys;
    }

    private int warmupKey(String key, Integer initialValue) {
        if (initialValue == null) {
            throw new IllegalStateException("库存未初始化，key=" + key);
        }
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
