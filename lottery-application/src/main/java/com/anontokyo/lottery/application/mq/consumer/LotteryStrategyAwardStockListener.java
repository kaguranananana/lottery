package com.anontokyo.lottery.application.mq.consumer;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
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

/**
 * 库存结算消费者。
 * 正确性由数据库兜底：
 *  - strategy_detail 条件扣减（award_surplus_count > 0）
 *  - award_stock_settlement 账本 UNIQUE(order_id)：重复投递/crash 重放只会结算一次
 * Redis PROCESSING 锁只是并发优化，不是事实来源。
 */
@Component
public class LotteryStrategyAwardStockListener {

    private final Logger logger = LoggerFactory.getLogger(LotteryStrategyAwardStockListener.class);

    @Resource
    private IStrategyRepository strategyRepository;

    @Resource
    private IAwardOrder awardOrder;

    @Resource
    private KafkaProducer kafkaProducer;

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
        try {
            // 已完成的历史订单快速短路（DB 状态，可依赖）
            if (awardOrder.isStockMqCompleted(settlementVO.getuId(), settlementVO.getOrderId())) {
                ack.acknowledge();
                return;
            }

            if (logger.isDebugEnabled()) {
                logger.debug("consume strategy award stock topic:{} message:{}", topic, message.get());
            }

            InvoiceVO invoiceVO = awardOrder.queryInvoiceByOrderId(settlementVO.getuId(), settlementVO.getOrderId());
            if (invoiceVO == null) {
                logger.warn("skip stale strategy award stock message, order not found uId:{} orderId:{}",
                        settlementVO.getuId(), settlementVO.getOrderId());
                ack.acknowledge();
                return;
            }

            boolean settled = strategyRepository.settleAwardStock(settlementVO);
            if (!settled) {
                // 条件扣减失败：Redis 与 DB 状态出现分歧，显式落 FAIL 并停止重试等待人工对账
                awardOrder.updateStockMqState(settlementVO.getuId(), settlementVO.getOrderId(),
                        Constants.MQState.FAIL.getCode());
                logger.error("settle award stock failed (stock gap), needs reconciliation "
                                + "uId:{} orderId:{} strategyId:{} awardId:{}",
                        settlementVO.getuId(), settlementVO.getOrderId(), settlementVO.getStrategyId(),
                        settlementVO.getAwardId());
                ack.acknowledge();
                return;
            }

            awardOrder.updateStockMqState(settlementVO.getuId(), settlementVO.getOrderId(),
                    Constants.MQState.COMPLETE.getCode());
            sendInvoiceMessage(invoiceVO);
            ack.acknowledge();
        } catch (Exception e) {
            // 不 ack：让 Kafka redeliver，settleAwardStock 的账本保证重放安全
            logger.error("consume strategy award stock failed topic:{} message:{}", topic, message.get(), e);
            throw e;
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
                            Constants.MQState.SENT.getCode());
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
