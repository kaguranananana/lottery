package com.anontokyo.lottery.application.mq.consumer;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
import com.anontokyo.lottery.domain.draw.repository.IDrawConfigRepository;
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
 *  - draw_config_prize 条件扣减（award_surplus_count > 0）
 *  - award_stock_settlement 账本 UNIQUE(order_id)：重复投递/crash 重放只会结算一次
 * Redis PROCESSING 锁只是并发优化，不是事实来源。
 */
@Component
public class StockSettlementListener {

    private final Logger logger = LoggerFactory.getLogger(StockSettlementListener.class);

    @Resource
    private IDrawConfigRepository drawConfigRepository;

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
            if (awardOrder.isStockSettlementComplete(settlementVO.getuId(), settlementVO.getOrderId())) {
                ack.acknowledge();
                return;
            }

            if (logger.isDebugEnabled()) {
                logger.debug("consume stock settlement topic:{} message:{}", topic, message.get());
            }

            AwardFulfillmentEvent fulfillmentEvent = awardOrder.queryFulfillmentEventByOrderId(settlementVO.getuId(), settlementVO.getOrderId());
            if (fulfillmentEvent == null) {
                logger.warn("skip stale stock settlement message, order not found uId:{} orderId:{}",
                        settlementVO.getuId(), settlementVO.getOrderId());
                ack.acknowledge();
                return;
            }

            boolean settled = drawConfigRepository.settleAwardStock(settlementVO);
            if (!settled) {
                // 账本与扣减事务已回滚：先持久化 BLOCKED，保留应结算订单等待对账。
                awardOrder.markStockSettlementFailed(settlementVO.getuId(), settlementVO.getOrderId());
                logger.error("settle award stock failed (stock gap), needs reconciliation "
                                + "uId:{} orderId:{} drawConfigId:{} awardId:{}",
                        settlementVO.getuId(), settlementVO.getOrderId(), settlementVO.getDrawConfigId(),
                        settlementVO.getAwardId());
                // 待对账状态写入失败会抛出而不 ACK；成功持久化后才停止本消息重投。
                ack.acknowledge();
                return;
            }

            awardOrder.updateStockSettlementEventStatus(settlementVO.getuId(), settlementVO.getOrderId(),
                    Constants.StockSettlementEventStatus.COMPLETE.getCode());
            sendInvoiceMessage(fulfillmentEvent);
            ack.acknowledge();
        } catch (Exception e) {
            // 不 ack：让 Kafka redeliver，settleAwardStock 的账本保证重放安全
            logger.error("consume stock settlement failed topic:{} message:{}", topic, message.get(), e);
            throw e;
        }
    }

    private void sendInvoiceMessage(AwardFulfillmentEvent fulfillmentEvent) {
        String uId = fulfillmentEvent.getuId();
        Long orderId = fulfillmentEvent.getOrderId();
        if (!invoiceMqEnabled) {
            awardOrder.updateFulfillmentEventStatus(uId, orderId, Constants.FulfillmentEventStatus.FAIL.getCode());
            if (logger.isDebugEnabled()) {
                logger.debug("invoice mq disabled after stock settlement uId:{} orderId:{}", uId, orderId);
            }
            return;
        }

        try {
            ListenableFuture<SendResult<String, Object>> future = kafkaProducer.sendAwardFulfillment(fulfillmentEvent);
            future.addCallback(new ListenableFutureCallback<SendResult<String, Object>>() {

                @Override
                public void onSuccess(SendResult<String, Object> result) {
                    awardOrder.updateFulfillmentEventStatus(fulfillmentEvent.getuId(), fulfillmentEvent.getOrderId(),
                            Constants.FulfillmentEventStatus.SENT.getCode());
                }

                @Override
                public void onFailure(Throwable throwable) {
                    awardOrder.updateFulfillmentEventStatus(fulfillmentEvent.getuId(), fulfillmentEvent.getOrderId(),
                            Constants.FulfillmentEventStatus.FAIL.getCode());
                    logger.error("invoice mq send failed after stock settlement uId:{} orderId:{}",
                            fulfillmentEvent.getuId(), fulfillmentEvent.getOrderId(), throwable);
                }
            });
        } catch (Exception e) {
            awardOrder.updateFulfillmentEventStatus(fulfillmentEvent.getuId(), fulfillmentEvent.getOrderId(),
                    Constants.FulfillmentEventStatus.FAIL.getCode());
            logger.error("invoice mq send exception after stock settlement uId:{} orderId:{}", fulfillmentEvent.getuId(),
                    fulfillmentEvent.getOrderId(), e);
        }
    }
}
