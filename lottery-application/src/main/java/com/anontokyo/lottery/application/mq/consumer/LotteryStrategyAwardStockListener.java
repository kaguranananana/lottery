package com.anontokyo.lottery.application.mq.consumer;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyAwardStockVO;
import com.anontokyo.lottery.domain.strategy.repository.IStrategyRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Optional;

/**
 * @description: Strategy award stock consumer
 * @author: anontokyo
 * @date: 2026/3/9
 */
@Component
public class LotteryStrategyAwardStockListener {

    private final Logger logger = LoggerFactory.getLogger(LotteryStrategyAwardStockListener.class);

    @Resource
    private IStrategyRepository strategyRepository;

    @KafkaListener(topics = "lottery_strategy_award_stock", groupId = "lottery")
    public void onMessage(ConsumerRecord<?, ?> record, Acknowledgment ack,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        Optional<?> message = Optional.ofNullable(record.value());
        if (!message.isPresent()) {
            return;
        }

        try {
            StrategyAwardStockVO strategyAwardStockVO = JSON.parseObject((String) message.get(),
                    StrategyAwardStockVO.class);
            logger.info("Consume mq message, async deduct strategy award stock, message:{}", message.get());
            strategyRepository.deductStock(strategyAwardStockVO.getStrategyId(), strategyAwardStockVO.getAwardId());

            // manual_immediate mode requires explicit ack to avoid duplicate re-consume.
            ack.acknowledge();
        } catch (Exception e) {
            logger.error("Consume mq message failed, topic:{} message:{}", topic, message.get(), e);
            throw e;
        }
    }

}
