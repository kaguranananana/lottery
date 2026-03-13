package com.anontokyo.lottery.domain.activity.service.partake;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.PartakeResult;
import com.anontokyo.lottery.domain.activity.model.res.StockResult;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.UserTakeActivityVO;
import com.anontokyo.lottery.domain.support.ids.IIdGenerator;

import javax.annotation.Resource;
import java.util.Map;

/**
 * @description: 活动领取模板抽象类, 只是用来编排流程
 * @author：anontokyo
 * @date: 2026/1/17
 */
public abstract class BaseActivityPartake extends ActivityPartakeSupport implements IActivityPartake {

    @Resource
    private Map<Constants.Ids, IIdGenerator> idGeneratorMap;

    @Override
    public PartakeResult doPartake(PartakeReq req) {

        // 1. 查询是否存在未执行抽奖领取活动单【user_take_activity 存在 state =
        // 0，领取了但抽奖过程失败的，可以直接返回领取结果继续抽奖】
        UserTakeActivityVO userTakeActivityVO = this.queryNoConsumedTakeActivityOrderFromCache(req.getActivityId(),
                req.getuId());
        if (null != userTakeActivityVO) {
            return buildPartakeResult(userTakeActivityVO.getStrategyId(), userTakeActivityVO.getTakeId(),
                    Constants.ResponseCode.NOT_CONSUMED_TAKE);
        }

        // 2. 查询活动账单 （状态、日期、库存、用户剩余次数）
        ActivityBillVO activityBillVO = super.queryActivityBill(req);

        // 3. 活动信息校验处理【活动库存、状态、日期、个人参与次数】
        Result checkResult = this.checkActivityBill(req, activityBillVO);
        if (!Constants.ResponseCode.SUCCESS.getCode().equals(checkResult.getCode())) {
            return new PartakeResult(checkResult.getCode(), checkResult.getInfo());
        }

        // 扣减活动库存，Redis 中维护的是剩余量，首次 miss 会按当前 DB 剩余量回填。
        StockResult subtractionActivityResult = this.substractionActivityStockByRedis(req.getuId(), req.getActivityId());
        if (!Constants.ResponseCode.SUCCESS.getCode().equals(subtractionActivityResult.getCode())) {
            return new PartakeResult(subtractionActivityResult.getCode(), subtractionActivityResult.getInfo());
        }

        // 当前实现不再使用库存 token 锁，保留空实现以兼容原有抽象。
        this.recoverActivityCacheStockByRedis(subtractionActivityResult.getStockKey());

        // 5. 插入领取活动信息【个人用户把活动信息写入到用户表】
        Long takeId = idGeneratorMap.get(Constants.Ids.SnowFlake).nextId();
        Result grabResult;
        try {
            grabResult = this.grabActivity(req, activityBillVO, takeId);
        } catch (Exception e) {
            // rollback one occupied stock slot when grab phase throws.
            this.recoverActivityStockByRedis(req.getActivityId());
            throw e;
        }
        if (!Constants.ResponseCode.SUCCESS.getCode().equals(grabResult.getCode())) {
            // rollback one occupied stock slot when grab phase returns failure code.
            this.recoverActivityStockByRedis(req.getActivityId());
            if (isGrabConflict(grabResult.getCode())) {
                UserTakeActivityVO noConsumedTakeActivityOrder = this.queryNoConsumedTakeActivityOrder(
                        req.getActivityId(), req.getuId());
                if (null != noConsumedTakeActivityOrder) {
                    return buildPartakeResult(noConsumedTakeActivityOrder.getStrategyId(),
                            noConsumedTakeActivityOrder.getTakeId(), Constants.ResponseCode.NOT_CONSUMED_TAKE);
                }
            }
            return new PartakeResult(grabResult.getCode(), grabResult.getInfo());
        }

        return buildPartakeResult(activityBillVO.getStrategyId(), takeId, activityBillVO.getStockCount(),
                subtractionActivityResult.getStockSurplusCount(), Constants.ResponseCode.SUCCESS);
    }

    protected abstract void recoverActivityCacheStockByRedis(String stockKey);

    protected abstract void recoverActivityStockByRedis(Long activityId);

    protected abstract StockResult substractionActivityStockByRedis(String uId, Long activityId);

    protected abstract UserTakeActivityVO queryNoConsumedTakeActivityOrderFromCache(Long activityId, String uId);

    protected abstract UserTakeActivityVO queryNoConsumedTakeActivityOrder(Long activityId, String s);

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

    protected abstract Result substractionActivityStock(PartakeReq req);

    protected abstract Result checkActivityBill(PartakeReq partake, ActivityBillVO bill);

    private boolean isGrabConflict(String code) {
        return Constants.ResponseCode.INDEX_DUP.getCode().equals(code)
                || Constants.ResponseCode.NO_UPDATE.getCode().equals(code);
    }

}
