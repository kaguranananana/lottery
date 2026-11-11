package com.anontokyo.lottery.infrastructure.lock;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.support.lock.IStockSettlementGuard;
import com.anontokyo.lottery.infrastructure.utils.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class RedisStockSettlementGuard implements IStockSettlementGuard {

    private final Logger logger = LoggerFactory.getLogger(RedisStockSettlementGuard.class);

    @Resource
    private RedisUtil redisUtil;

    @Value("${lottery.cache.strategy-award-stock-mq-dedup-redis-ttl-seconds:86400}")
    private long drawConfigPrizeStockMqDedupRedisTtlSeconds;

    @Value("${lottery.cache.strategy-award-stock-mq-processing-redis-ttl-seconds:300}")
    private long drawConfigPrizeStockMqProcessingRedisTtlSeconds;

    @Value("${lottery.cache.strategy-award-stock-mq-dispatch-redis-ttl-seconds:60}")
    private long drawConfigPrizeStockMqDispatchRedisTtlSeconds;

    @Override
    public boolean tryClaimProcessing(Long orderId) {
        if (orderId == null) {
            return true;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_DRAW_CONFIG_PRIZE_STOCK_MQ_PROCESSING(orderId);
        try {
            return redisUtil.setIfAbsent(key, "1", drawConfigPrizeStockMqProcessingRedisTtlSeconds);
        } catch (Exception e) {
            logger.warn("claim drawConfig award stock mq processing key failed, orderId:{}", orderId, e);
            return false;
        }
    }

    @Override
    public void releaseProcessing(Long orderId) {
        if (orderId == null) {
            return;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_DRAW_CONFIG_PRIZE_STOCK_MQ_PROCESSING(orderId);
        try {
            redisUtil.delStringKey(key);
        } catch (Exception e) {
            logger.warn("release drawConfig award stock mq processing key failed, orderId:{}", orderId, e);
        }
    }

    @Override
    public boolean tryClaimDispatch(Long orderId) {
        if (orderId == null) {
            return false;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_DRAW_CONFIG_PRIZE_STOCK_MQ_DISPATCHING(orderId);
        try {
            return redisUtil.setIfAbsent(key, "1", drawConfigPrizeStockMqDispatchRedisTtlSeconds);
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
        String key = Constants.RedisKey.KEY_LOTTERY_DRAW_CONFIG_PRIZE_STOCK_MQ_DISPATCHING(orderId);
        try {
            redisUtil.delStringKey(key);
        } catch (Exception e) {
            logger.warn("clear drawConfig award stock mq dispatch marker failed, orderId:{}", orderId, e);
        }
    }
}
