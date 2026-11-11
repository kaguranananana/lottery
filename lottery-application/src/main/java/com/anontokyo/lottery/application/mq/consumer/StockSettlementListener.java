package com.anontokyo.lottery.application.mq.consumer;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.application.mq.producer.OutboxDispatcher;
import com.anontokyo.lottery.common.Constants.StockSettlementEventStatus;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.draw.repository.IDrawConfigRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/** 库存扣减与唯一账本同事务，重复消息以持久化事实结算。 */
@Component
public class StockSettlementListener {
    private final Logger logger = LoggerFactory.getLogger(StockSettlementListener.class);

    @Resource
    private IDrawConfigRepository drawConfigRepository;

    @Resource
    private IOrderRepository orderRepository;

    @Resource
    private OutboxDispatcher outbox;

    @KafkaListener(topics = "lottery_strategy_award_stock",
            groupId = "${lottery.kafka.consumer.strategy-award-stock-group-id:lottery-strategy-award-stock}",
            concurrency = "${lottery.kafka.consumer.strategy-award-stock-concurrency:4}")
    public void onMessage(ConsumerRecord<?, ?> record, Acknowledgment ack,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        if (record.value() == null) {
            ack.acknowledge();
            return;
        }
        try {
            AwardStockSettlementVO message = JSON.parseObject((String) record.value(), AwardStockSettlementVO.class);
            DrawOrderVO order = orderRepository.findOrder(message.getuId(), message.getOrderId());
            if (order == null) {
                logger.warn("stock event order missing user:{} order:{}", message.getuId(), message.getOrderId());
                ack.acknowledge();
                return;
            }
            Integer state = order.getStockSettlementEventStatus();
            if (StockSettlementEventStatus.COMPLETE.getCode().equals(state)
                    || StockSettlementEventStatus.BLOCKED.getCode().equals(state)) {
                // 阻断订单只能由对账恢复。迟到或重复消息不重新尝试扣减。
                ack.acknowledge();
                return;
            }

            // 由订单快照决定被扣减的奖品，避免陈旧消息带入其它配置或奖品。
            AwardStockSettlementVO settlement = new AwardStockSettlementVO();
            BeanUtils.copyProperties(order, settlement);
            if (!drawConfigRepository.settleAwardStock(settlement)) {
                orderRepository.markStockSettlementFailed(order.getuId(), order.getOrderId());
                logger.error("stock reconciliation required user:{} order:{} config:{} award:{}",
                        order.getuId(), order.getOrderId(), order.getDrawConfigId(), order.getAwardId());
                ack.acknowledge();
                return;
            }

            // 库存完成同时登记发奖义务。即使进程在提交 Kafka 之前退出，发奖扫描仍能恢复。
            orderRepository.updateStockSettlementEventStatus(order.getuId(), order.getOrderId(),
                    StockSettlementEventStatus.COMPLETE.getCode());
            AwardFulfillmentEvent award = new AwardFulfillmentEvent();
            BeanUtils.copyProperties(order, award);
            outbox.afterStockSettled(award);
            ack.acknowledge();
        } catch (RuntimeException e) {
            logger.error("stock consume failed topic:{} key:{}", topic, record.key(), e);
            throw e;
        }
    }
}
