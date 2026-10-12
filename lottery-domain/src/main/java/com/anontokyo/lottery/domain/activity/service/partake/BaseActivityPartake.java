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
        // 没有请求号就无法保证持久幂等，直接拒绝参与。
        if (req.getRequestId() == null || req.getRequestId().trim().isEmpty()) {
            return new PartakeResult(Constants.ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    Constants.ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }
        // 每次创建前查询数据库，缓存失效不等于没有未完成记录。
        UserTakeActivityVO userTakeActivityVO = this.queryNoConsumedTakeActivityOrder(
                req.getActivityId(), req.getuId());
        if (userTakeActivityVO != null) {
            return resolveUnfinishedTake(req, userTakeActivityVO);
        }

        ActivityBillVO activityBillVO = super.queryActivityBill(req);
        Result checkResult = this.checkActivityBill(req, activityBillVO);
        if (!Constants.ResponseCode.SUCCESS.getCode().equals(checkResult.getCode())) {
            return new PartakeResult(checkResult.getCode(), checkResult.getInfo());
        }

        // 活动库存仍使用现有 Redis 预扣逻辑，运行期缺失库存不会回填。
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
                    // 唯一约束竞争失败后也必须核对归属，不能把 A 交给 B。
                    return resolveUnfinishedTake(req, noConsumedTakeActivityOrder);
                }
            }
            return new PartakeResult(grabResult.getCode(), grabResult.getInfo());
        }

        return buildPartakeResult(activityBillVO.getStrategyId(), takeId, activityBillVO.getStockCount(),
                subtractionActivityResult.getStockSurplusCount(), Constants.ResponseCode.SUCCESS);
    }

    private PartakeResult resolveUnfinishedTake(PartakeReq req, UserTakeActivityVO take) {
        // 无归属历史记录不能自动绑定到新请求，保留机会并显式要求核查。
        if (take.getRequestId() == null || take.getRequestId().trim().isEmpty()
                || !Constants.TaskState.NO_USED.getCode().equals(take.getState())) {
            return new PartakeResult(Constants.ResponseCode.TAKE_STATE_ERROR.getCode(),
                    Constants.ResponseCode.TAKE_STATE_ERROR.getInfo());
        }
        // 仅创建记录的原请求可以继续消费同一 take。
        if (req.getRequestId().equals(take.getRequestId())) {
            return buildPartakeResult(take.getStrategyId(), take.getTakeId(),
                    Constants.ResponseCode.NOT_CONSUMED_TAKE);
        }
        // 新请求只取得原请求号用于恢复，不取得 take 的消费资格。
        PartakeResult result = new PartakeResult(Constants.ResponseCode.UNFINISHED_DRAW.getCode(),
                Constants.ResponseCode.UNFINISHED_DRAW.getInfo());
        result.setPendingRequestId(take.getRequestId());
        return result;
    }

    protected abstract void recoverActivityStockByRedis(Long activityId);

    protected abstract StockResult substractionActivityStockByRedis(String uId, Long activityId);

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
