package com.anontokyo.lottery.infrastructure.lock;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.support.lock.IInvoiceMqProcessGuard;
import com.anontokyo.lottery.infrastructure.utils.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class RedisInvoiceMqProcessGuard implements IInvoiceMqProcessGuard {

    private final Logger logger = LoggerFactory.getLogger(RedisInvoiceMqProcessGuard.class);

    @Resource
    private RedisUtil redisUtil;

    @Value("${lottery.cache.invoice-mq-processing-redis-ttl-seconds:300}")
    private long invoiceMqProcessingRedisTtlSeconds;

    @Override
    public boolean tryClaimProcessing(Long orderId) {
        if (orderId == null) {
            return false;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_INVOICE_MQ_PROCESSING(orderId);
        try {
            return redisUtil.setIfAbsent(key, "1", invoiceMqProcessingRedisTtlSeconds);
        } catch (Exception e) {
            logger.warn("claim invoice mq processing key failed, orderId:{}", orderId, e);
            return false;
        }
    }

    @Override
    public void releaseProcessing(Long orderId) {
        if (orderId == null) {
            return;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_INVOICE_MQ_PROCESSING(orderId);
        try {
            redisUtil.delStringKey(key);
        } catch (Exception e) {
            logger.warn("release invoice mq processing key failed, orderId:{}", orderId, e);
        }
    }
}
