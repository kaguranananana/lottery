package com.anontokyo.lottery.application.process.draw.impl;

import com.anontokyo.lottery.application.process.draw.IActivityDrawProcess;
import com.anontokyo.lottery.application.process.draw.req.DrawProcessReq;
import com.anontokyo.lottery.application.process.draw.res.DrawProcessResult;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.PartakeResult;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;
import com.anontokyo.lottery.domain.activity.model.vo.UserTakeActivityVO;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.domain.activity.repository.IUserTakeActivityRepository;
import com.anontokyo.lottery.domain.activity.service.partake.IActivityPartake;
import com.anontokyo.lottery.domain.award.model.req.AwardOrderReq;
import com.anontokyo.lottery.domain.award.model.res.AwardOrderResult;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
import com.anontokyo.lottery.domain.support.lock.IActivityDrawProcessGuard;
import com.anontokyo.lottery.domain.strategy.model.req.DrawReq;
import com.anontokyo.lottery.domain.strategy.model.res.DrawResult;
import com.anontokyo.lottery.domain.strategy.model.vo.DrawAwardInfo;
import com.anontokyo.lottery.domain.strategy.service.draw.IDrawExec;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.UUID;

@Service
public class ActivityProcessImpl implements IActivityDrawProcess {

    private static final long SLOW_DRAW_PROCESS_THRESHOLD_MS = 3000L;

    private final Logger logger = LoggerFactory.getLogger(ActivityProcessImpl.class);

    @Resource
    private IActivityPartake activityPartake;

    @Resource
    private IAwardOrder awardOrder;

    @Resource
    private IDrawExec drawExec;

    @Resource
    private IActivityRepository activityRepository;

    @Resource
    private IUserTakeActivityRepository userTakeActivityRepository;

    @Resource
    private IOrderRepository orderRepository;

    @Resource
    private IActivityDrawProcessGuard activityDrawProcessGuard;

    @Override
    public DrawProcessResult doDrawProcess(DrawProcessReq req) {
        // 并发防重入锁：token 为本请求随机值，只有属主可释放（Lua compare-and-delete）
        String token = UUID.randomUUID().toString();
        boolean inflightClaimed = activityDrawProcessGuard.tryClaim(req.getActivityId(), req.getuId(), token);
        if (!inflightClaimed) {
            return new DrawProcessResult(Constants.ResponseCode.DRAW_IN_PROGRESS.getCode(),
                    Constants.ResponseCode.DRAW_IN_PROGRESS.getInfo());
        }

        long processStart = System.nanoTime();
        long partakeCostMs = 0L;
        long drawCostMs = 0L;
        long settleCostMs = 0L;
        try {
            // requestId 幂等快速路径：同 requestId 的重试永远回到同一个 take / order
            UserTakeActivityVO existingTake = findTakeByRequestId(req);
            Long strategyId;
            Long takeId;
            if (existingTake != null
                    && Constants.TaskState.USED.getCode().equals(existingTake.getState())) {
                DrawProcessResult replayed = replayConsumedTake(req, existingTake);
                logSlowDrawProcess(req, replayed.getCode(), processStart, partakeCostMs, drawCostMs, settleCostMs);
                return replayed;
            }
            if (existingTake != null
                    && Constants.TaskState.NO_USED.getCode().equals(existingTake.getState())) {
                // 上次请求中断遗留的未消费 take：复用，不再新建参与记录、不重复消耗次数
                strategyId = existingTake.getStrategyId();
                takeId = existingTake.getTakeId();
                logger.info("draw reuses unfinished take by requestId uId:{} activityId:{} requestId:{} takeId:{}",
                        req.getuId(), req.getActivityId(), req.getRequestId(), takeId);
            } else {
                long stageStart = System.nanoTime();
                PartakeResult partakeResult =
                        activityPartake.doPartake(new PartakeReq(req.getuId(), req.getActivityId(), req.getRequestId()));
                partakeCostMs = elapsedMillis(stageStart);
                if (!Constants.ResponseCode.SUCCESS.getCode().equals(partakeResult.getCode())
                        && !Constants.ResponseCode.NOT_CONSUMED_TAKE.getCode().equals(partakeResult.getCode())) {
                    DrawProcessResult result = new DrawProcessResult(partakeResult.getCode(), partakeResult.getInfo());
                    logSlowDrawProcess(req, result.getCode(), processStart, partakeCostMs, drawCostMs, settleCostMs);
                    return result;
                }
                strategyId = partakeResult.getStrategyId();
                takeId = partakeResult.getTakeId();
            }

            long stageStart = System.nanoTime();
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
                    logSlowDrawProcess(req, result.getCode(), processStart, partakeCostMs, drawCostMs, settleCostMs);
                    return result;
                }
                DrawProcessResult result = new DrawProcessResult(Constants.ResponseCode.LOSING_DRAW.getCode(),
                        Constants.ResponseCode.LOSING_DRAW.getInfo());
                result.setTakeId(takeId);
                logSlowDrawProcess(req, result.getCode(), processStart, partakeCostMs, drawCostMs, settleCostMs);
                return result;
            }

            DrawAwardInfo drawAwardInfo = drawResult.getDrawAwardInfo();

            stageStart = System.nanoTime();
            AwardOrderResult orderResult = awardOrder.placeOrder(buildAwardOrderReq(req, strategyId, takeId, drawAwardInfo));
            settleCostMs = elapsedMillis(stageStart);
            if (!Constants.ResponseCode.SUCCESS.getCode().equals(orderResult.getCode())) {
                drawExec.recoverDrawAwardStock(strategyId, drawAwardInfo.getAwardId());
                DrawProcessResult result = new DrawProcessResult(orderResult.getCode(), orderResult.getInfo());
                logSlowDrawProcess(req, result.getCode(), processStart, partakeCostMs, drawCostMs, settleCostMs);
                return result;
            }

            DrawProcessResult result = new DrawProcessResult(Constants.ResponseCode.SUCCESS.getCode(),
                    Constants.ResponseCode.SUCCESS.getInfo(), drawAwardInfo);
            result.setTakeId(takeId);
            if (orderResult.getInvoiceVO() != null) {
                result.setOrderId(orderResult.getInvoiceVO().getOrderId());
            }
            logSlowDrawProcess(req, result.getCode(), processStart, partakeCostMs, drawCostMs,
                    settleCostMs);
            return result;
        } finally {
            activityDrawProcessGuard.release(req.getActivityId(), req.getuId(), token);
        }
    }

    private UserTakeActivityVO findTakeByRequestId(DrawProcessReq req) {
        if (StringUtils.isBlank(req.getRequestId())) {
            return null;
        }
        try {
            return userTakeActivityRepository.queryTakeActivityByRequestId(
                    req.getActivityId(), req.getuId(), req.getRequestId());
        } catch (Exception e) {
            logger.warn("query take by requestId failed, degrade to normal flow. uId:{} activityId:{} requestId:{}",
                    req.getuId(), req.getActivityId(), req.getRequestId(), e);
            return null;
        }
    }

    /**
     * 同 requestId 重试时回放已消费 take 的最终结果：
     * 有订单 → 返回原中奖信息；无订单 → 该 take 抽奖未中。
     */
    private DrawProcessResult replayConsumedTake(DrawProcessReq req, UserTakeActivityVO take) {
        logger.info("replay consumed take for idempotent retry uId:{} activityId:{} requestId:{} takeId:{}",
                req.getuId(), req.getActivityId(), req.getRequestId(), take.getTakeId());

        InvoiceVO order = orderRepository.queryOrderByTakeId(req.getuId(), take.getTakeId());
        if (order == null) {
            DrawProcessResult result = new DrawProcessResult(Constants.ResponseCode.LOSING_DRAW.getCode(),
                    Constants.ResponseCode.LOSING_DRAW.getInfo());
            result.setTakeId(take.getTakeId());
            return result;
        }

        DrawAwardInfo awardInfo = new DrawAwardInfo(order.getAwardId(), order.getAwardType(),
                order.getAwardName(), order.getAwardContent());
        awardInfo.setGrantType(order.getGrantType());
        awardInfo.setGrantDate(order.getGrantDate());

        DrawProcessResult result = new DrawProcessResult(Constants.ResponseCode.SUCCESS.getCode(),
                Constants.ResponseCode.SUCCESS.getInfo(), awardInfo);
        result.setTakeId(take.getTakeId());
        result.setOrderId(order.getOrderId());
        return result;
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

    private void logSlowDrawProcess(DrawProcessReq req, String resultCode, long processStart, long partakeCostMs,
            long drawCostMs, long settleCostMs) {
        long totalCostMs = elapsedMillis(processStart);
        if (totalCostMs < SLOW_DRAW_PROCESS_THRESHOLD_MS) {
            return;
        }
        logger.warn(
                "slow draw process uId:{} activityId:{} requestId:{} resultCode:{} total={}ms partake={}ms draw={}ms settle={}ms stockDispatch=async",
                req.getuId(), req.getActivityId(), req.getRequestId(), resultCode, totalCostMs, partakeCostMs, drawCostMs, settleCostMs);
    }

    private long elapsedMillis(long startNanoTime) {
        return (System.nanoTime() - startNanoTime) / 1_000_000L;
    }
}
