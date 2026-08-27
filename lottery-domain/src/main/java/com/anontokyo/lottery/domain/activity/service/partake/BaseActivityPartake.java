package com.anontokyo.lottery.domain.activity.service.partake;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.PartakeResult;
import com.anontokyo.lottery.domain.activity.model.res.StockResult;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.UserTakeActivityVO;
import com.anontokyo.lottery.domain.support.ids.IdGenerator;

import javax.annotation.Resource;

/**
 * Activity partake template. It only orchestrates the domain flow.
 */
public abstract class BaseActivityPartake extends ActivityPartakeSupport implements IActivityPartake {

    @Resource
    private IdGenerator idGenerator;

    @Override
    public PartakeResult doPartake(PartakeReq req) {
        UserTakeActivityVO userTakeActivityVO = this.queryNoConsumedTakeActivityOrderFromCache(
                req.getActivityId(), req.getuId());
        if (userTakeActivityVO != null) {
            return buildPartakeResult(userTakeActivityVO.getStrategyId(), userTakeActivityVO.getTakeId(),
                    Constants.ResponseCode.NOT_CONSUMED_TAKE);
        }

        ActivityBillVO activityBillVO = super.queryActivityBill(req);
        Result checkResult = this.checkActivityBill(req, activityBillVO);
        if (!Constants.ResponseCode.SUCCESS.getCode().equals(checkResult.getCode())) {
            return new PartakeResult(checkResult.getCode(), checkResult.getInfo());
        }

        // Redis keeps remaining inventory; first miss backfills from DB remaining stock.
        StockResult subtractionActivityResult = this.substractionActivityStockByRedis(req.getuId(), req.getActivityId());
        if (!Constants.ResponseCode.SUCCESS.getCode().equals(subtractionActivityResult.getCode())) {
            return new PartakeResult(subtractionActivityResult.getCode(), subtractionActivityResult.getInfo());
        }
        activityBillVO.setStockSurplusCount(subtractionActivityResult.getStockSurplusCount());

        Long takeId = idGenerator.nextId();
        Result grabResult;
        try {
            grabResult = this.grabActivity(req, activityBillVO, takeId);
        } catch (Exception e) {
            this.recoverActivityStockByRedis(req.getActivityId());
            throw e;
        }
        if (!Constants.ResponseCode.SUCCESS.getCode().equals(grabResult.getCode())) {
            this.recoverActivityStockByRedis(req.getActivityId());
            if (isGrabConflict(grabResult.getCode())) {
                UserTakeActivityVO noConsumedTakeActivityOrder = this.queryNoConsumedTakeActivityOrder(
                        req.getActivityId(), req.getuId());
                if (noConsumedTakeActivityOrder != null) {
                    return buildPartakeResult(noConsumedTakeActivityOrder.getStrategyId(),
                            noConsumedTakeActivityOrder.getTakeId(), Constants.ResponseCode.NOT_CONSUMED_TAKE);
                }
            }
            return new PartakeResult(grabResult.getCode(), grabResult.getInfo());
        }

        return buildPartakeResult(activityBillVO.getStrategyId(), takeId, activityBillVO.getStockCount(),
                subtractionActivityResult.getStockSurplusCount(), Constants.ResponseCode.SUCCESS);
    }

    protected abstract void recoverActivityStockByRedis(Long activityId);

    protected abstract StockResult substractionActivityStockByRedis(String uId, Long activityId);

    protected abstract UserTakeActivityVO queryNoConsumedTakeActivityOrderFromCache(Long activityId, String uId);

    protected abstract UserTakeActivityVO queryNoConsumedTakeActivityOrder(Long activityId, String uId);

    private PartakeResult buildPartakeResult(Long strategyId, Long takeId, Integer stockCount,
            Integer stockSurplusCount, Constants.ResponseCode code) {
        PartakeResult partakeResult = new PartakeResult(code.getCode(), code.getInfo());
        partakeResult.setStrategyId(strategyId);
        partakeResult.setTakeId(takeId);
        partakeResult.setStockCount(stockCount);
        partakeResult.setStockSurplusCount(stockSurplusCount);
        return partakeResult;
    }

    private PartakeResult buildPartakeResult(Long strategyId, Long takeId, Constants.ResponseCode code) {
        PartakeResult partakeResult = new PartakeResult(code.getCode(), code.getInfo());
        partakeResult.setStrategyId(strategyId);
        partakeResult.setTakeId(takeId);
        return partakeResult;
    }

    protected abstract Result grabActivity(PartakeReq req, ActivityBillVO activityBillVO, Long takeId);

    protected abstract Result checkActivityBill(PartakeReq partake, ActivityBillVO bill);

    private boolean isGrabConflict(String code) {
        return Constants.ResponseCode.INDEX_DUP.getCode().equals(code)
                || Constants.ResponseCode.NO_UPDATE.getCode().equals(code);
    }
}
