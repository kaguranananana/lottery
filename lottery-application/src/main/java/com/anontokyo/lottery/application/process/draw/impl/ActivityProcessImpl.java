package com.anontokyo.lottery.application.process.draw.impl;
import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
import com.anontokyo.lottery.application.process.draw.IActivityDrawProcess;
import com.anontokyo.lottery.application.process.draw.req.DrawProcessReq;
import com.anontokyo.lottery.application.process.draw.res.DrawProcessResult;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.PartakeResult;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityPartakeRecordVO;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.domain.activity.service.partake.IActivityPartake;
import com.anontokyo.lottery.domain.award.model.req.AwardOrderReq;
import com.anontokyo.lottery.domain.award.model.res.AwardOrderResult;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
import com.anontokyo.lottery.domain.support.lock.IActivityDrawProcessGuard;
import com.anontokyo.lottery.domain.strategy.model.req.DrawReq;
import com.anontokyo.lottery.domain.strategy.model.res.DrawResult;
import com.anontokyo.lottery.domain.strategy.model.vo.DrawAwardInfo;
import com.anontokyo.lottery.domain.strategy.service.draw.IDrawExec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

@Service
public class ActivityProcessImpl implements IActivityDrawProcess {

    private static final long SLOW_DRAW_PROCESS_THRESHOLD_MS = 3000L;

    private final Logger logger = LoggerFactory.getLogger(ActivityProcessImpl.class);

    @Resource
    private IActivityPartake activityPartake;

    @Resource
    private KafkaProducer kafkaProducer;

    @Resource
    private IAwardOrder awardOrder;

    @Resource
    private IDrawExec drawExec;

    @Resource
    private IActivityRepository activityRepository;

    @Resource
    private IActivityDrawProcessGuard activityDrawProcessGuard;

    @Value("${lottery.kafka.activity-partake.enabled:true}")
    private boolean activityPartakeMqEnabled;

    @Override
    public DrawProcessResult doDrawProcess(DrawProcessReq req) {
        boolean inflightClaimed = activityDrawProcessGuard.tryClaim(req.getActivityId(), req.getuId());
        if (!inflightClaimed) {
            return new DrawProcessResult(Constants.ResponseCode.DRAW_IN_PROGRESS.getCode(),
                    Constants.ResponseCode.DRAW_IN_PROGRESS.getInfo());
        }

        long processStart = System.nanoTime();
        long partakeCostMs = 0L;
        long dispatchCostMs = 0L;
        long drawCostMs = 0L;
        long settleCostMs = 0L;
        try {
            long stageStart = System.nanoTime();
            PartakeResult partakeResult = activityPartake.doPartake(new PartakeReq(req.getuId(), req.getActivityId()));
            partakeCostMs = elapsedMillis(stageStart);
            if (!Constants.ResponseCode.SUCCESS.getCode().equals(partakeResult.getCode())
                    && !Constants.ResponseCode.NOT_CONSUMED_TAKE.getCode().equals(partakeResult.getCode())) {
                DrawProcessResult result = new DrawProcessResult(partakeResult.getCode(), partakeResult.getInfo());
                logSlowDrawProcess(req, result.getCode(), processStart, partakeCostMs, dispatchCostMs, drawCostMs,
                        settleCostMs);
                return result;
            }

            Long strategyId = partakeResult.getStrategyId();
            Long takeId = partakeResult.getTakeId();

            if (activityPartakeMqEnabled && Constants.ResponseCode.SUCCESS.getCode().equals(partakeResult.getCode())) {
                stageStart = System.nanoTime();
                kafkaProducer.sendLotteryActivityPartakeRecord(buildActivityPartakeRecord(req, partakeResult));
                dispatchCostMs = elapsedMillis(stageStart);
            }

            stageStart = System.nanoTime();
            DrawResult drawResult = drawExec.doDrawExec(new DrawReq(req.getuId(), strategyId, String.valueOf(takeId)));
            drawCostMs = elapsedMillis(stageStart);
            if (Constants.DrawState.FAIL.getCode().equals(drawResult.getDrawState())) {
                stageStart = System.nanoTime();
                Result consumeTakeResult = activityPartake.consumeTakeActivity(req.getuId(), req.getActivityId(), takeId);
                settleCostMs = elapsedMillis(stageStart);
                if (!Constants.ResponseCode.SUCCESS.getCode().equals(consumeTakeResult.getCode())
                        && !Constants.ResponseCode.NO_UPDATE.getCode().equals(consumeTakeResult.getCode())) {
                    DrawProcessResult result = new DrawProcessResult(consumeTakeResult.getCode(),
                            consumeTakeResult.getInfo());
                    logSlowDrawProcess(req, result.getCode(), processStart, partakeCostMs, dispatchCostMs, drawCostMs,
                            settleCostMs);
                    return result;
                }
                DrawProcessResult result = new DrawProcessResult(Constants.ResponseCode.LOSING_DRAW.getCode(),
                        Constants.ResponseCode.LOSING_DRAW.getInfo());
                logSlowDrawProcess(req, result.getCode(), processStart, partakeCostMs, dispatchCostMs, drawCostMs,
                        settleCostMs);
                return result;
            }

            DrawAwardInfo drawAwardInfo = drawResult.getDrawAwardInfo();

            stageStart = System.nanoTime();
            AwardOrderResult orderResult = awardOrder.placeOrder(buildAwardOrderReq(req, strategyId, takeId, drawAwardInfo));
            settleCostMs = elapsedMillis(stageStart);
            if (!Constants.ResponseCode.SUCCESS.getCode().equals(orderResult.getCode())) {
                drawExec.recoverDrawAwardStock(strategyId, drawAwardInfo.getAwardId());
                DrawProcessResult result = new DrawProcessResult(orderResult.getCode(), orderResult.getInfo());
                logSlowDrawProcess(req, result.getCode(), processStart, partakeCostMs, dispatchCostMs, drawCostMs,
                        settleCostMs);
                return result;
            }

            DrawProcessResult result = new DrawProcessResult(Constants.ResponseCode.SUCCESS.getCode(),
                    Constants.ResponseCode.SUCCESS.getInfo(), drawAwardInfo);
            result.setTakeId(takeId);
            if (orderResult.getInvoiceVO() != null) {
                result.setOrderId(orderResult.getInvoiceVO().getOrderId());
            }
            logSlowDrawProcess(req, result.getCode(), processStart, partakeCostMs, dispatchCostMs, drawCostMs,
                    settleCostMs);
            return result;
        } finally {
            activityDrawProcessGuard.release(req.getActivityId(), req.getuId());
        }
    }

    @Override
    public List<ActivityVO> queryRunningActivityList() {
        return activityRepository.queryRunningActivityList();
    }

    private AwardOrderReq buildAwardOrderReq(DrawProcessReq req, Long strategyId, Long takeId,
            DrawAwardInfo drawAwardInfo) {
        AwardOrderReq awardOrderReq = new AwardOrderReq();
        awardOrderReq.setuId(req.getuId());
        awardOrderReq.setTakeId(takeId);
        awardOrderReq.setActivityId(req.getActivityId());
        awardOrderReq.setStrategyId(strategyId);
        awardOrderReq.setStrategyMode(drawAwardInfo.getStrategyMode());
        awardOrderReq.setGrantType(drawAwardInfo.getGrantType());
        awardOrderReq.setGrantDate(drawAwardInfo.getGrantDate());
        awardOrderReq.setAwardId(drawAwardInfo.getAwardId());
        awardOrderReq.setAwardType(drawAwardInfo.getAwardType());
        awardOrderReq.setAwardName(drawAwardInfo.getAwardName());
        awardOrderReq.setAwardContent(drawAwardInfo.getAwardContent());
        return awardOrderReq;
    }

    private ActivityPartakeRecordVO buildActivityPartakeRecord(DrawProcessReq req, PartakeResult partakeResult) {
        ActivityPartakeRecordVO recordVO = new ActivityPartakeRecordVO();
        recordVO.setuId(req.getuId());
        recordVO.setActivityId(req.getActivityId());
        recordVO.setTakeId(partakeResult.getTakeId());
        recordVO.setStockCount(partakeResult.getStockCount());
        recordVO.setStockSurplusCount(partakeResult.getStockSurplusCount());
        return recordVO;
    }

    private void logSlowDrawProcess(DrawProcessReq req, String resultCode, long processStart, long partakeCostMs,
            long dispatchCostMs, long drawCostMs, long settleCostMs) {
        long totalCostMs = elapsedMillis(processStart);
        if (totalCostMs < SLOW_DRAW_PROCESS_THRESHOLD_MS) {
            return;
        }
        logger.warn(
                "slow draw process uId:{} activityId:{} resultCode:{} total={}ms partake={}ms dispatch={}ms draw={}ms settle={}ms stockDispatch=async",
                req.getuId(), req.getActivityId(), resultCode, totalCostMs, partakeCostMs, dispatchCostMs,
                drawCostMs, settleCostMs);
    }

    private long elapsedMillis(long startNanoTime) {
        return (System.nanoTime() - startNanoTime) / 1_000_000L;
    }
}
