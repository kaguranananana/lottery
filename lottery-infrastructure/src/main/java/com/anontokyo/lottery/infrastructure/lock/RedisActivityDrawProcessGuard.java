package com.anontokyo.lottery.infrastructure.lock;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.support.lock.IActivityDrawProcessGuard;
import com.anontokyo.lottery.infrastructure.utils.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class RedisActivityDrawProcessGuard implements IActivityDrawProcessGuard {

    private final Logger logger = LoggerFactory.getLogger(RedisActivityDrawProcessGuard.class);

    @Resource
    private RedisUtil redisUtil;

    @Value("${lottery.cache.draw-inflight-redis-ttl-seconds:3}")
    private long drawInflightRedisTtlSeconds;

    @Override
    public boolean tryClaim(Long activityId, String uId) {
        String inflightKey = Constants.RedisKey.KEY_LOTTERY_DRAW_IN_FLIGHT(activityId, uId);
        try {
            return redisUtil.setIfAbsent(inflightKey, "1", drawInflightRedisTtlSeconds);
        } catch (Exception e) {
            logger.warn("claim draw inflight key failed, degrade to unlocked flow key:{}", inflightKey, e);
            return true;
        }
    }

    @Override
    public void release(Long activityId, String uId) {
        String inflightKey = Constants.RedisKey.KEY_LOTTERY_DRAW_IN_FLIGHT(activityId, uId);
        try {
            redisUtil.delStringKey(inflightKey);
        } catch (Exception e) {
            logger.warn("release draw inflight key failed key:{}", inflightKey, e);
        }
    }
}
