package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.aggregates.ActivityInfoLimitPageRich;
import com.anontokyo.lottery.domain.activity.model.req.ActivityInfoLimitPageReq;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.StockResult;
import com.anontokyo.lottery.domain.activity.model.vo.*;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.infrastructure.cache.TwoLevelCacheService;
import com.anontokyo.lottery.infrastructure.dao.*;
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
            twoLevelCacheService.evictAwardCache(Constants.RedisKey.KEY_LOTTERY_CACHE_AWARD(awardVO.getAwardId()));
        }
    }

    @Override
    public void addStrategy(StrategyVO strategy) {
        Strategy req = new Strategy();
        BeanUtils.copyProperties(strategy, req);
        strategyDao.insert(req);
        twoLevelCacheService
                .evictStrategyRichCache(Constants.RedisKey.KEY_LOTTERY_CACHE_STRATEGY_RICH(strategy.getStrategyId()));
        redisUtil.delStringKey(Constants.RedisKey.KEY_LOTTERY_CACHE_STRATEGY_NO_STOCK_AWARD(strategy.getStrategyId()));
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
            twoLevelCacheService.evictStrategyRichCache(Constants.RedisKey.KEY_LOTTERY_CACHE_STRATEGY_RICH(strategyId));
            redisUtil.delStringKey(Constants.RedisKey.KEY_LOTTERY_CACHE_STRATEGY_NO_STOCK_AWARD(strategyId));
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
        Activity activity = twoLevelCacheService.getActivityCache(
                Constants.RedisKey.KEY_LOTTERY_CACHE_ACTIVITY(req.getActivityId()),
                Activity.class,
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

    @Override
    public StockResult substractionActivityStockByRedis(String uId, Long activityId) {
        String stockKey = Constants.RedisKey.KEY_LOTTERY_ACTIVITY_STOCK_COUNT(activityId);
        long currentStockSurplusCount = initActivityStockIfAbsent(activityId, stockKey);
        if (currentStockSurplusCount <= 0) {
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

    private long initActivityStockIfAbsent(Long activityId, String stockKey) {
        try {
            String cacheValue = redisUtil.get(stockKey);
            if (cacheValue != null && cacheValue.length() > 0) {
                return Long.parseLong(cacheValue);
            }
        } catch (Exception e) {
            logger.warn("read activity stock from redis failed, activityId:{} key:{}", activityId, stockKey, e);
        }

        Activity activity = activityDao.queryActivityById(activityId);
        long stockSurplusCount = activity == null || activity.getStockSurplusCount() == null ? 0L
                : activity.getStockSurplusCount();
        try {
            boolean initialized = redisUtil.setIfAbsent(stockKey, String.valueOf(stockSurplusCount));
            if (initialized) {
                return stockSurplusCount;
            }
            String latestValue = redisUtil.get(stockKey);
            if (latestValue != null && latestValue.length() > 0) {
                return Long.parseLong(latestValue);
            }
        } catch (Exception e) {
            logger.warn("init activity stock in redis failed, activityId:{} key:{}", activityId, stockKey, e);
        }
        return stockSurplusCount;
    }
}
