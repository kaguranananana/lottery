package com.anontokyo.lottery.domain.activity.service.partake.impl;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.StockResult;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.UserTakeActivityVO;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.domain.activity.repository.IUserTakeActivityRepository;
import com.anontokyo.lottery.domain.activity.service.partake.BaseActivityPartake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class ActivityPartakeImpl extends BaseActivityPartake {

    private final Logger logger = LoggerFactory.getLogger(ActivityPartakeImpl.class);

    @Resource
    private IUserTakeActivityRepository userTakeActivityRepository;

    @Resource
    private IActivityRepository activityRepository;

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
    protected Result grabActivity(PartakeReq partake, ActivityBillVO bill, Long takeId) {
        Constants.ResponseCode code = userTakeActivityRepository.takeParticipation(
                bill, partake.getPartakeDate(), takeId, partake.getRequestId());
        return code == Constants.ResponseCode.SUCCESS
                ? Result.buildSuccessResult()
                : new Result(code.getCode(), code.getInfo());
    }

    @Override
    public Result consumeTakeActivity(String uId, Long activityId, Long takeId) {
        Constants.ResponseCode code = userTakeActivityRepository.consumeTake(uId, activityId, takeId);
        if (code != Constants.ResponseCode.SUCCESS) {
            logger.error("consume take activity failed activityId:{} uId:{} takeId:{}", activityId, uId, takeId);
            return Result.buildResult(code);
        }
        return Result.buildSuccessResult();
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
    protected UserTakeActivityVO queryNoConsumedTakeActivityOrder(Long activityId, String uId) {
        return userTakeActivityRepository.queryNoConsumedTakeActivityOrder(activityId, uId);
    }
}
