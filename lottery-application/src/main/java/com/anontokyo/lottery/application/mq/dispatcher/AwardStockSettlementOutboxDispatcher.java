package com.anontokyo.lottery.application.mq.dispatcher;

import cn.bugstack.middleware.db.router.strategy.IDBRouterStrategy;
import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
import com.anontokyo.lottery.domain.support.lock.IStrategyAwardStockMqGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

import javax.annotation.Resource;
import java.util.List;

@Component
public class AwardStockSettlementOutboxDispatcher {

    private final Logger logger = LoggerFactory.getLogger(AwardStockSettlementOutboxDispatcher.class);

    @Resource
    private IDBRouterStrategy dbRouter;

    @Resource
    private IAwardOrder awardOrder;

    @Resource
    private KafkaProducer kafkaProducer;

    @Resource
    private IStrategyAwardStockMqGuard strategyAwardStockMqGuard;

    @Value("${lottery.kafka.dispatcher.strategy-award-stock.enabled:true}")
    private boolean stockSettlementDispatcherEnabled;

    @Value("${lottery.kafka.dispatcher.strategy-award-stock-batch-size:200}")
    private int stockSettlementDispatchBatchSize;

    @Scheduled(
            initialDelayString = "${lottery.kafka.dispatcher.strategy-award-stock-initial-delay-millis:1000}",
            fixedDelayString = "${lottery.kafka.dispatcher.strategy-award-stock-fixed-delay-millis:1000}")
    public void dispatchPending() {
        if (!stockSettlementDispatcherEnabled) {
            return;
        }

        int dbCount = dbRouter.dbCount();
        int tbCount = dbRouter.tbCount();
        for (int dbIdx = 1; dbIdx <= dbCount; dbIdx++) {
            for (int tbIdx = 0; tbIdx < tbCount; tbIdx++) {
                List<AwardStockSettlementVO> settlementVOList =
                        awardOrder.scanAwardStockMqState(dbIdx, tbIdx, stockSettlementDispatchBatchSize);
                if (settlementVOList.isEmpty()) {
                    continue;
                }

                for (AwardStockSettlementVO settlementVO : settlementVOList) {
                    dispatch(settlementVO);
                }
            }
        }
    }

    public void dispatch(AwardStockSettlementVO settlementVO) {
        if (!stockSettlementDispatcherEnabled || settlementVO == null || settlementVO.getOrderId() == null) {
            return;
        }

        Long orderId = settlementVO.getOrderId();
        if (awardOrder.isStockMqCompleted(settlementVO.getuId(), orderId)) {
            strategyAwardStockMqGuard.clearDispatchMarker(orderId);
            return;
        }

        if (!strategyAwardStockMqGuard.tryClaimDispatch(orderId)) {
            return;
        }

        try {
            ListenableFuture<SendResult<String, Object>> future = kafkaProducer.sendStrategyAwardStock(settlementVO);
            future.addCallback(new ListenableFutureCallback<SendResult<String, Object>>() {

                @Override
                public void onSuccess(SendResult<String, Object> result) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("dispatch award stock settlement mq success uId:{} orderId:{}",
                                settlementVO.getuId(), settlementVO.getOrderId());
                    }
                }

                @Override
                public void onFailure(Throwable throwable) {
                    awardOrder.updateStockMqState(settlementVO.getuId(), settlementVO.getOrderId(),
                            Constants.MQState.FAIL.getCode());
                    strategyAwardStockMqGuard.clearDispatchMarker(settlementVO.getOrderId());
                    logger.error("dispatch award stock settlement mq failed uId:{} orderId:{}",
                            settlementVO.getuId(), settlementVO.getOrderId(), throwable);
                }
            });
        } catch (Exception e) {
            awardOrder.updateStockMqState(settlementVO.getuId(), settlementVO.getOrderId(),
                    Constants.MQState.FAIL.getCode());
            strategyAwardStockMqGuard.clearDispatchMarker(settlementVO.getOrderId());
            logger.error("dispatch award stock settlement mq exception uId:{} orderId:{}",
                    settlementVO.getuId(), settlementVO.getOrderId(), e);
        }
    }
}
