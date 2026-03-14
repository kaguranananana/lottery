package com.anontokyo.lottery.domain.award.service.order.impl;

import cn.bugstack.middleware.db.router.strategy.IDBRouterStrategy;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.activity.repository.IUserTakeActivityRepository;
import com.anontokyo.lottery.domain.award.model.req.AwardOrderReq;
import com.anontokyo.lottery.domain.award.model.res.AwardOrderResult;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
import com.anontokyo.lottery.domain.support.ids.IIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

@Service
public class AwardOrderImpl implements IAwardOrder {

    private static final long SLOW_AWARD_ORDER_THRESHOLD_MS = 1000L;

    private final Logger logger = LoggerFactory.getLogger(AwardOrderImpl.class);

    @Resource
    private IDBRouterStrategy dbRouter;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private IUserTakeActivityRepository userTakeActivityRepository;

    @Resource
    private IOrderRepository orderRepository;

    @Resource
    private Map<Constants.Ids, IIdGenerator> idGeneratorMap;

    @Override
    public AwardOrderResult placeOrder(AwardOrderReq awardOrderReq) {
        long processStart = System.nanoTime();
        long routeCostMs = 0L;
        long txCostMs = 0L;
        long lockCostMs = 0L;
        long saveOrderCostMs = 0L;

        DrawOrderVO drawOrder = buildDrawOrder(awardOrderReq);

        try {
            long stageStart = System.nanoTime();
            dbRouter.doRouter(drawOrder.getuId());
            routeCostMs = elapsedMillis(stageStart);

            final long[] lockCostHolder = {0L};
            final long[] saveOrderCostHolder = {0L};
            stageStart = System.nanoTime();
            Result result = transactionTemplate.execute(status -> {
                try {
                    long txStageStart = System.nanoTime();
                    int lockCount = userTakeActivityRepository.lockTakeActivity(
                            drawOrder.getuId(), drawOrder.getActivityId(), drawOrder.getTakeId());
                    lockCostHolder[0] = elapsedMillis(txStageStart);
                    if (lockCount == 0) {
                        status.setRollbackOnly();
                        logger.warn("award order lock take activity failed uId:{} activityId:{} takeId:{}",
                                drawOrder.getuId(), drawOrder.getActivityId(), drawOrder.getTakeId());
                        return Result.buildResult(Constants.ResponseCode.NO_UPDATE);
                    }

                    txStageStart = System.nanoTime();
                    orderRepository.saveDrawOrder(drawOrder);
                    saveOrderCostHolder[0] = elapsedMillis(txStageStart);
                } catch (DuplicateKeyException e) {
                    status.setRollbackOnly();
                    logger.warn("award order duplicate uId:{} activityId:{} orderId:{}", drawOrder.getuId(),
                            drawOrder.getActivityId(), drawOrder.getOrderId(), e);
                    return Result.buildResult(Constants.ResponseCode.INDEX_DUP);
                }

                return Result.buildSuccessResult();
            });
            txCostMs = elapsedMillis(stageStart);
            lockCostMs = lockCostHolder[0];
            saveOrderCostMs = saveOrderCostHolder[0];

            if (result == null) {
                AwardOrderResult failResult = buildFailResult("record draw order failed");
                logPlaceOrder(drawOrder, failResult.getCode(), failResult.getInfo(), processStart, routeCostMs,
                        txCostMs, lockCostMs, saveOrderCostMs);
                return failResult;
            }

            if (!Constants.ResponseCode.SUCCESS.getCode().equals(result.getCode())) {
                AwardOrderResult failResult = new AwardOrderResult(result.getCode(), result.getInfo());
                logPlaceOrder(drawOrder, failResult.getCode(), failResult.getInfo(), processStart, routeCostMs,
                        txCostMs, lockCostMs, saveOrderCostMs);
                return failResult;
            }

            AwardOrderResult successResult = new AwardOrderResult(
                    Constants.ResponseCode.SUCCESS.getCode(),
                    Constants.ResponseCode.SUCCESS.getInfo(),
                    buildInvoice(drawOrder),
                    buildAwardStockSettlement(drawOrder));
            logPlaceOrder(drawOrder, successResult.getCode(), successResult.getInfo(), processStart, routeCostMs,
                    txCostMs, lockCostMs, saveOrderCostMs);
            return successResult;
        } catch (RuntimeException e) {
            logPlaceOrderException(drawOrder, processStart, routeCostMs, txCostMs, lockCostMs, saveOrderCostMs, e);
            throw e;
        } finally {
            dbRouter.clear();
        }
    }

    @Override
    public void updateInvoiceMqState(String uId, Long orderId, Integer mqState) {
        orderRepository.updateInvoiceMqState(uId, orderId, mqState);
    }

    @Override
    public void updateStockMqState(String uId, Long orderId, Integer stockMqState) {
        orderRepository.updateStockMqState(uId, orderId, stockMqState);
    }

    @Override
    public List<InvoiceVO> scanInvoiceMqState(int dbCount, int tbCount) {
        try {
            dbRouter.setDBKey(dbCount);
            dbRouter.setTBKey(tbCount);
            return orderRepository.scanInvoiceMqState();
        } finally {
            dbRouter.clear();
        }
    }

    @Override
    public List<AwardStockSettlementVO> scanAwardStockMqState(int dbCount, int tbCount, int limit) {
        try {
            dbRouter.setDBKey(dbCount);
            dbRouter.setTBKey(tbCount);
            return orderRepository.scanAwardStockMqState(limit);
        } finally {
            dbRouter.clear();
        }
    }

    @Override
    public InvoiceVO queryInvoiceByOrderId(String uId, Long orderId) {
        return orderRepository.queryInvoiceByOrderId(uId, orderId);
    }

    @Override
    public boolean isStockMqCompleted(String uId, Long orderId) {
        return orderRepository.isStockMqCompleted(uId, orderId);
    }

    private DrawOrderVO buildDrawOrder(AwardOrderReq awardOrderReq) {
        DrawOrderVO drawOrderVO = new DrawOrderVO();
        drawOrderVO.setuId(awardOrderReq.getuId());
        drawOrderVO.setTakeId(awardOrderReq.getTakeId());
        drawOrderVO.setActivityId(awardOrderReq.getActivityId());
        drawOrderVO.setOrderId(idGeneratorMap.get(Constants.Ids.SnowFlake).nextId());
        drawOrderVO.setStrategyId(awardOrderReq.getStrategyId());
        drawOrderVO.setStrategyMode(awardOrderReq.getStrategyMode());
        drawOrderVO.setGrantType(awardOrderReq.getGrantType());
        drawOrderVO.setGrantDate(awardOrderReq.getGrantDate());
        drawOrderVO.setGrantState(Constants.GrantState.INIT.getCode());
        drawOrderVO.setAwardId(awardOrderReq.getAwardId());
        drawOrderVO.setAwardType(awardOrderReq.getAwardType());
        drawOrderVO.setAwardName(awardOrderReq.getAwardName());
        drawOrderVO.setAwardContent(awardOrderReq.getAwardContent());
        drawOrderVO.setMqState(Constants.MQState.INIT.getCode());
        drawOrderVO.setStockMqState(Constants.MQState.INIT.getCode());
        return drawOrderVO;
    }

    private InvoiceVO buildInvoice(DrawOrderVO drawOrderVO) {
        InvoiceVO invoiceVO = new InvoiceVO();
        invoiceVO.setuId(drawOrderVO.getuId());
        invoiceVO.setOrderId(drawOrderVO.getOrderId());
        invoiceVO.setAwardId(drawOrderVO.getAwardId());
        invoiceVO.setAwardType(drawOrderVO.getAwardType());
        invoiceVO.setAwardName(drawOrderVO.getAwardName());
        invoiceVO.setAwardContent(drawOrderVO.getAwardContent());
        return invoiceVO;
    }

    private AwardStockSettlementVO buildAwardStockSettlement(DrawOrderVO drawOrderVO) {
        AwardStockSettlementVO settlementVO = new AwardStockSettlementVO();
        settlementVO.setuId(drawOrderVO.getuId());
        settlementVO.setOrderId(drawOrderVO.getOrderId());
        settlementVO.setStrategyId(drawOrderVO.getStrategyId());
        settlementVO.setAwardId(drawOrderVO.getAwardId());
        return settlementVO;
    }

    private AwardOrderResult buildFailResult(String info) {
        return new AwardOrderResult(Constants.ResponseCode.UN_ERROR.getCode(), info);
    }

    private void logPlaceOrder(DrawOrderVO drawOrder, String resultCode, String resultInfo, long processStart,
            long routeCostMs, long txCostMs, long lockCostMs, long saveOrderCostMs) {
        long totalCostMs = elapsedMillis(processStart);
        boolean success = Constants.ResponseCode.SUCCESS.getCode().equals(resultCode);
        if (!success || totalCostMs >= SLOW_AWARD_ORDER_THRESHOLD_MS) {
            logger.warn(
                    "award order timing uId:{} activityId:{} orderId:{} awardId:{} resultCode:{} resultInfo:{} total={}ms route={}ms tx={}ms lockTake={}ms saveOrder={}ms",
                    drawOrder.getuId(), drawOrder.getActivityId(), drawOrder.getOrderId(), drawOrder.getAwardId(),
                    resultCode, resultInfo, totalCostMs, routeCostMs, txCostMs, lockCostMs, saveOrderCostMs);
            return;
        }
        if (logger.isDebugEnabled()) {
            logger.debug(
                    "award order timing uId:{} activityId:{} orderId:{} awardId:{} resultCode:{} total={}ms route={}ms tx={}ms lockTake={}ms saveOrder={}ms",
                    drawOrder.getuId(), drawOrder.getActivityId(), drawOrder.getOrderId(), drawOrder.getAwardId(),
                    resultCode, totalCostMs, routeCostMs, txCostMs, lockCostMs, saveOrderCostMs);
        }
    }

    private void logPlaceOrderException(DrawOrderVO drawOrder, long processStart, long routeCostMs, long txCostMs,
            long lockCostMs, long saveOrderCostMs, RuntimeException e) {
        long totalCostMs = elapsedMillis(processStart);
        logger.error(
                "award order exception uId:{} activityId:{} orderId:{} awardId:{} total={}ms route={}ms tx={}ms lockTake={}ms saveOrder={}ms",
                drawOrder.getuId(), drawOrder.getActivityId(), drawOrder.getOrderId(), drawOrder.getAwardId(),
                totalCostMs, routeCostMs, txCostMs, lockCostMs, saveOrderCostMs, e);
    }

    private long elapsedMillis(long startNanoTime) {
        return (System.nanoTime() - startNanoTime) / 1_000_000L;
    }
}
