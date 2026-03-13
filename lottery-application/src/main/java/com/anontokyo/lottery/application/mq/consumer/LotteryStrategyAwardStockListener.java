package com.anontokyo.lottery.application.mq.consumer;

import com.anontokyo.lottery.domain.strategy.model.vo.StrategyAwardStockVO;
import com.anontokyo.lottery.domain.strategy.repository.IStrategyRepository;
import com.alibaba.fastjson.JSON;
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
 * @description: 策略奖品库存消费者, 监听Redis扣减后的异步落库消息
 * @author：anontokyo
 * @date: 2026/3/9
 */
@Component
public class LotteryStrategyAwardStockListener {

    private Logger logger = LoggerFactory.getLogger(LotteryStrategyAwardStockListener.class);

    @Resource
    private IStrategyRepository strategyRepository;

    @KafkaListener(topics = "lottery_strategy_award_stock", groupId = "lottery")
    public void onMessage(ConsumerRecord<?, ?> record, Acknowledgment ack, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        Optional<?> message = Optional.ofNullable(record.value());

        // 1. 判断消息是否存在
        if (!message.isPresent()) {
            return;
        }

        // 2. 转化对象
        StrategyAwardStockVO strategyAwardStockVO = JSON.parseObject((String) message.get(), StrategyAwardStockVO.class);
        logger.info("消费MQ消息, 异步扣减策略奖品库存 message：{}", message.get());

        // 3. 更新数据库库存（strategy_detail表的award_surplus_count - 1）
        strategyRepository.deductStock(strategyAwardStockVO.getStrategyId(), strategyAwardStockVO.getAwardId());
    }

}

