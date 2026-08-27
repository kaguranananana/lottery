package com.anontokyo.lottery.infrastructure.lock;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.support.lock.IStrategyAwardStockMqGuard;
import com.anontokyo.lottery.infrastructure.utils.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class RedisStrategyAwardStockMqGuard implements IStrategyAwardStockMqGuard {

    private final Logger logger = LoggerFactory.getLogger(RedisStrategyAwardStockMqGuard.class);

    @Resource
    private RedisUtil redisUtil;

    @Value("${lottery.cache.strategy-award-stock-mq-dedup-redis-ttl-seconds:86400}")
    private long strategyAwardStockMqDedupRedisTtlSeconds;

    @Value("${lottery.cache.strategy-award-stock-mq-processing-redis-ttl-seconds:300}")
    private long strategyAwardStockMqProcessingRedisTtlSeconds;

    @Value("${lottery.cache.strategy-award-stock-mq-dispatch-redis-ttl-seconds:60}")
    private long strategyAwardStockMqDispatchRedisTtlSeconds;

    @Override
    public boolean tryClaimProcessing(Long orderId) {
        if (orderId == null) {
            return true;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_PROCESSING(orderId);
        try {
            return redisUtil.setIfAbsent(key, "1", strategyAwardStockMqProcessingRedisTtlSeconds);
        } catch (Exception e) {
            logger.warn("claim strategy award stock mq processing key failed, orderId:{}", orderId, e);
            return false;
        }
    }

    @Override
    public void releaseProcessing(Long orderId) {
        if (orderId == null) {
            return;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_PROCESSING(orderId);
        try {
            redisUtil.delStringKey(key);
        } catch (Exception e) {
            logger.warn("release strategy award stock mq processing key failed, orderId:{}", orderId, e);
        }
    }

    @Override
    public boolean tryClaimDispatch(Long orderId) {
        if (orderId == null) {
            return false;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_DISPATCHING(orderId);
        try {
            return redisUtil.setIfAbsent(key, "1", strategyAwardStockMqDispatchRedisTtlSeconds);
        } catch (Exception e) {
            logger.warn("claim award stock settlement dispatch marker failed orderId:{}", orderId, e);
            return false;
        }
    }

    @Override
    public void clearDispatchMarker(Long orderId) {
        if (orderId == null) {
            return;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_DISPATCHING(orderId);
        try {
            redisUtil.delStringKey(key);
        } catch (Exception e) {
            logger.warn("clear strategy award stock mq dispatch marker failed, orderId:{}", orderId, e);
        }
    }
}
