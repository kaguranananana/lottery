package com.anontokyo.lottery.domain.activity.service.partake.impl;

import cn.bugstack.middleware.db.router.strategy.IDBRouterStrategy;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.StockResult;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityPartakeRecordVO;
import com.anontokyo.lottery.domain.activity.model.vo.UserTakeActivityVO;
import com.anontokyo.lottery.domain.activity.repository.IUserTakeActivityRepository;
import com.anontokyo.lottery.domain.activity.service.partake.BaseActivityPartake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;

@Service
public class ActivityPartakeImpl extends BaseActivityPartake {

    private final Logger logger = LoggerFactory.getLogger(ActivityPartakeImpl.class);

    @Resource
    private IDBRouterStrategy dbRouter;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private IUserTakeActivityRepository userTakeActivityRepository;

    @Override
    protected Result checkActivityBill(PartakeReq partake, ActivityBillVO bill) {
        if (!Constants.ActivityState.DOING.getCode().equals(bill.getState())) {
            logger.warn("activity unavailable state:{}", bill.getState());
            return Result.buildResult(Constants.ResponseCode.UN_ERROR, "activity unavailable");
        }

        if (bill.getBeginDateTime().after(partake.getPartakeDate())
                || bill.getEndDateTime().before(partake.getPartakeDate())) {
            logger.warn("activity not in valid time range begin:{} end:{}", bill.getBeginDateTime(), bill.getEndDateTime());
            return Result.buildResult(Constants.ResponseCode.UN_ERROR, "activity not in valid time range");
        }

        if (bill.getStockSurplusCount() <= 0) {
            logger.warn("activity stock unavailable stockSurplusCount:{}", bill.getStockSurplusCount());
            return Result.buildResult(Constants.ResponseCode.UN_ERROR, "activity stock unavailable");
        }
        return Result.buildSuccessResult();
    }

    @Override
    protected Result substractionActivityStock(PartakeReq req) {
        int count = activityRepository.substractionActivityStock(req.getActivityId());
        if (count == 0) {
            logger.error("subtract activity stock failed activityId:{}", req.getActivityId());
            return Result.buildResult(Constants.ResponseCode.NO_UPDATE);
        }
        return Result.buildSuccessResult();
    }

    @Override
    protected Result grabActivity(PartakeReq partake, ActivityBillVO bill, Long takeId) {
        try {
            dbRouter.doRouter(partake.getuId());
            return transactionTemplate.execute(transactionStatus -> {
                try {
                    int updateCount = userTakeActivityRepository.subtractionLeftCount(bill);
                    if (updateCount == 0) {
                        transactionStatus.setRollbackOnly();
                        logger.error("grab activity subtract user quota failed activityId:{} uId:{}",
                                partake.getActivityId(), partake.getuId());
                        return Result.buildResult(Constants.ResponseCode.NO_UPDATE);
                    }

                    userTakeActivityRepository.takeActivity(bill, partake.getPartakeDate(), takeId);
                } catch (DuplicateKeyException e) {
                    transactionStatus.setRollbackOnly();
                    logger.error("grab activity duplicate key activityId:{} uId:{}", partake.getActivityId(),
                            partake.getuId(), e);
                    return Result.buildResult(Constants.ResponseCode.INDEX_DUP);
                }

                return Result.buildSuccessResult();
            });
        } finally {
            dbRouter.clear();
        }
    }

    @Override
    protected UserTakeActivityVO queryNoConsumedTakeActivityOrderFromCache(Long activityId, String uId) {
        return userTakeActivityRepository.queryNoConsumedTakeActivityOrderFromCache(activityId, uId);
    }

    @Override
    protected UserTakeActivityVO queryNoConsumedTakeActivityOrder(Long activityId, String uId) {
        return userTakeActivityRepository.queryNoConsumedTakeActivityOrder(activityId, uId);
    }

    @Override
    public Result consumeTakeActivity(String uId, Long activityId, Long takeId) {
        try {
            dbRouter.doRouter(uId);
            int lockCount = userTakeActivityRepository.lockTakeActivity(uId, activityId, takeId);
            if (lockCount == 0) {
                logger.error("consume take activity failed activityId:{} uId:{} takeId:{}", activityId, uId, takeId);
                return Result.buildResult(Constants.ResponseCode.NO_UPDATE);
            }
            return Result.buildSuccessResult();
        } finally {
            dbRouter.clear();
        }
    }

    @Override
    protected StockResult substractionActivityStockByRedis(String uId, Long activityId) {
        return activityRepository.substractionActivityStockByRedis(uId, activityId);
    }

    @Override
    protected void recoverActivityStockByRedis(Long activityId) {
        activityRepository.recoverActivityStockByRedis(activityId);
    }

    @Override
    protected void recoverActivityCacheStockByRedis(String stockKey) {
        activityRepository.recoverActivityCacheStockByRedis(stockKey);
    }

    @Override
    public void updateActivityStock(ActivityPartakeRecordVO activityPartakeRecordVO) {
        userTakeActivityRepository.updateActivityStock(activityPartakeRecordVO);
    }
}
