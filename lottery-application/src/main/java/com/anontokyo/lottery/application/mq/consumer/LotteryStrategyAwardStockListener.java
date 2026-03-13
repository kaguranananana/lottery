package com.anontokyo.lottery.application.mq.consumer;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyAwardStockVO;
import com.anontokyo.lottery.domain.strategy.repository.IStrategyRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${lottery.cache.strategy-award-stock-mq-dedup-redis-ttl-seconds:86400}")
    private long strategyAwardStockMqDedupRedisTtlSeconds;

    public void onMessage(ConsumerRecord<?, ?> record, Acknowledgment ack,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        Optional<?> message = Optional.ofNullable(record.value());
        if (!message.isPresent()) {
            ack.acknowledge();
            return;
        }

        try {
            StrategyAwardStockVO strategyAwardStockVO = JSON.parseObject((String) message.get(),
                    StrategyAwardStockVO.class);
            logger.info("Consume mq message, async deduct strategy award stock, message:{}", message.get());

            if (isProcessed(strategyAwardStockVO.getOrderId())) {
                logger.info("Skip duplicated strategy award stock message, orderId:{}", strategyAwardStockVO.getOrderId());
                ack.acknowledge();
                return;
            }

            boolean deductSuccess = strategyRepository.deductStock(
                    strategyAwardStockVO.getStrategyId(), strategyAwardStockVO.getAwardId());
            if (!deductSuccess) {
                throw new IllegalStateException("deduct strategy award stock failed");
            }

            markProcessed(strategyAwardStockVO.getOrderId());

            // manual_immediate mode requires explicit ack to avoid duplicate re-consume.
            ack.acknowledge();
        } catch (Exception e) {
            logger.error("Consume mq message failed, topic:{} message:{}", topic, message.get(), e);
            throw e;
        }
    }

    private boolean isProcessed(Long orderId) {
        if (orderId == null) {
            return false;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_DONE(orderId);
        try {
            String value = stringRedisTemplate.opsForValue().get(key);
            return value != null && value.length() > 0;
        } catch (Exception e) {
            logger.warn("Read strategy award stock mq dedup key failed, orderId:{}", orderId, e);
            return false;
        }
    }

    private void markProcessed(Long orderId) {
        if (orderId == null) {
            return;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_DONE(orderId);
        try {
            stringRedisTemplate.opsForValue().set(key, "1", strategyAwardStockMqDedupRedisTtlSeconds,
                    TimeUnit.SECONDS);
        } catch (Exception e) {
            // Do not fail the consumer after DB stock has already been deducted.
            logger.warn("Set strategy award stock mq dedup key failed, orderId:{}", orderId, e);
        }
    }
}
