package com.anontokyo.lottery.application.mq.consumer;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
import com.anontokyo.lottery.domain.support.lock.IStrategyAwardStockMqGuard;
import com.anontokyo.lottery.domain.strategy.repository.IStrategyRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

import javax.annotation.Resource;
import java.util.Optional;

@Component
public class LotteryStrategyAwardStockListener {

    private final Logger logger = LoggerFactory.getLogger(LotteryStrategyAwardStockListener.class);

    @Resource
    private IStrategyRepository strategyRepository;

    @Resource
    private IAwardOrder awardOrder;

    @Resource
    private KafkaProducer kafkaProducer;

    @Resource
    private IStrategyAwardStockMqGuard strategyAwardStockMqGuard;

    @Value("${lottery.kafka.invoice.enabled:true}")
    private boolean invoiceMqEnabled;

    @KafkaListener(topics = "lottery_strategy_award_stock",
            groupId = "${lottery.kafka.consumer.strategy-award-stock-group-id:lottery-strategy-award-stock}",
            concurrency = "${lottery.kafka.consumer.strategy-award-stock-concurrency:8}")
    public void onMessage(ConsumerRecord<?, ?> record, Acknowledgment ack,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        Optional<?> message = Optional.ofNullable(record.value());
        if (!message.isPresent()) {
            ack.acknowledge();
            return;
        }

        AwardStockSettlementVO settlementVO = JSON.parseObject((String) message.get(), AwardStockSettlementVO.class);
        boolean claimed = false;
        try {
            if (strategyAwardStockMqGuard.isProcessed(settlementVO.getOrderId())
                    || awardOrder.isStockMqCompleted(settlementVO.getuId(), settlementVO.getOrderId())) {
                ack.acknowledge();
                return;
            }

            claimed = strategyAwardStockMqGuard.tryClaimProcessing(settlementVO.getOrderId());
            if (!claimed) {
                ack.acknowledge();
                return;
            }

            if (logger.isDebugEnabled()) {
                logger.debug("consume strategy award stock topic:{} message:{}", topic, message.get());
            }

            InvoiceVO invoiceVO = awardOrder.queryInvoiceByOrderId(settlementVO.getuId(), settlementVO.getOrderId());
            if (invoiceVO == null) {
                strategyAwardStockMqGuard.markProcessed(settlementVO.getOrderId());
                strategyAwardStockMqGuard.clearDispatchMarker(settlementVO.getOrderId());
                logger.warn("skip stale strategy award stock message, order not found uId:{} orderId:{}",
                        settlementVO.getuId(), settlementVO.getOrderId());
                ack.acknowledge();
                return;
            }

            boolean deductSuccess = strategyRepository.deductStock(
                    settlementVO.getStrategyId(), settlementVO.getAwardId());
            if (!deductSuccess) {
                awardOrder.updateStockMqState(settlementVO.getuId(), settlementVO.getOrderId(),
                        Constants.MQState.FAIL.getCode());
                logger.error("deduct strategy award stock failed uId:{} orderId:{} strategyId:{} awardId:{}",
                        settlementVO.getuId(), settlementVO.getOrderId(), settlementVO.getStrategyId(),
                        settlementVO.getAwardId());
                ack.acknowledge();
                return;
            }

            awardOrder.updateStockMqState(settlementVO.getuId(), settlementVO.getOrderId(),
                    Constants.MQState.COMPLETE.getCode());
            strategyAwardStockMqGuard.markProcessed(settlementVO.getOrderId());
            strategyAwardStockMqGuard.clearDispatchMarker(settlementVO.getOrderId());
            sendInvoiceMessage(invoiceVO);
            ack.acknowledge();
        } catch (Exception e) {
            logger.error("consume strategy award stock failed topic:{} message:{}", topic, message.get(), e);
            throw e;
        } finally {
            if (claimed) {
                strategyAwardStockMqGuard.releaseProcessing(settlementVO.getOrderId());
            }
        }
    }

    private void sendInvoiceMessage(InvoiceVO invoiceVO) {
        String uId = invoiceVO.getuId();
        Long orderId = invoiceVO.getOrderId();
        if (!invoiceMqEnabled) {
            awardOrder.updateInvoiceMqState(uId, orderId, Constants.MQState.FAIL.getCode());
            if (logger.isDebugEnabled()) {
                logger.debug("invoice mq disabled after stock settlement uId:{} orderId:{}", uId, orderId);
            }
            return;
        }

        try {
            ListenableFuture<SendResult<String, Object>> future = kafkaProducer.sendLotteryInvoice(invoiceVO);
            future.addCallback(new ListenableFutureCallback<SendResult<String, Object>>() {

                @Override
                public void onSuccess(SendResult<String, Object> result) {
                    awardOrder.updateInvoiceMqState(invoiceVO.getuId(), invoiceVO.getOrderId(),
                            Constants.MQState.COMPLETE.getCode());
                }

                @Override
                public void onFailure(Throwable throwable) {
                    awardOrder.updateInvoiceMqState(invoiceVO.getuId(), invoiceVO.getOrderId(),
                            Constants.MQState.FAIL.getCode());
                    logger.error("invoice mq send failed after stock settlement uId:{} orderId:{}",
                            invoiceVO.getuId(), invoiceVO.getOrderId(), throwable);
                }
            });
        } catch (Exception e) {
            awardOrder.updateInvoiceMqState(invoiceVO.getuId(), invoiceVO.getOrderId(),
                    Constants.MQState.FAIL.getCode());
            logger.error("invoice mq send exception after stock settlement uId:{} orderId:{}", invoiceVO.getuId(),
                    invoiceVO.getOrderId(), e);
        }
    }
}
