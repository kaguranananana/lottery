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

import javax.annotation.Resource;
import java.util.ArrayList;
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

    /**
     * 活动启动前的库存预热（READY→RUNNING 时由调度触发；SETNX 幂等可重复执行）：
     * 活动 Redis 初始剩余库存 + 该策略下所有奖品 Redis 初始剩余库存，初始值取自此刻的 MySQL。
     */
    @Override
    public int warmupStockForActivity(Long activityId) {
        Activity activity = activityDao.queryActivityById(activityId);
        if (activity == null) {
            logger.warn("warmup stock failed, activity not found activityId:{}", activityId);
            return 0;
        }
        int warmedKeys = 0;
        warmedKeys += warmupKey(Constants.RedisKey.KEY_LOTTERY_ACTIVITY_STOCK_COUNT(activityId),
                activity.getStockSurplusCount());

        if (activity.getStrategyId() != null) {
            StrategyDetail query = new StrategyDetail();
            query.setStrategyId(activity.getStrategyId());
            List<StrategyDetail> details = strategyDetailDao.queryStrategyDetailList(activity.getStrategyId());
            for (StrategyDetail detail : details) {
                warmedKeys += warmupKey(
                        Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_COUNT(detail.getStrategyId(),
                                detail.getAwardId()),
                        detail.getAwardSurplusCount());
            }
        }
        logger.info("stock warmup finished activityId:{} keysWarmup:{}", activityId, warmedKeys);
        return warmedKeys;
    }

    private int warmupKey(String key, Integer initialValue) {
        if (initialValue == null) {
            initialValue = 0;
        }
        try {
            boolean set = redisUtil.setIfAbsent(key, String.valueOf(initialValue));
            return set ? 1 : 0;
        } catch (Exception e) {
            logger.error("warmup stock key failed, key:{}", key, e);
            return 0;
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
