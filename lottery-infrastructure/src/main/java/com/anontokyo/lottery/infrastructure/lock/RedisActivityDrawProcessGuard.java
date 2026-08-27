package com.anontokyo.lottery.infrastructure.lock;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.support.lock.IActivityDrawProcessGuard;
import com.anontokyo.lottery.infrastructure.utils.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 并发防重入锁（性能控制，不是业务幂等的事实来源——幂等由 user_take_activity.uk_request_id 兜底）。
 */
@Component
public class RedisActivityDrawProcessGuard implements IActivityDrawProcessGuard {

    private final Logger logger = LoggerFactory.getLogger(RedisActivityDrawProcessGuard.class);

    @Resource
    private RedisUtil redisUtil;

    @Value("${lottery.cache.draw-inflight-redis-ttl-seconds:3}")
    private long drawInflightRedisTtlSeconds;

    @Override
    public boolean tryClaim(Long activityId, String uId, String token) {
        String inflightKey = Constants.RedisKey.KEY_LOTTERY_DRAW_IN_FLIGHT(activityId, uId);
        try {
            return redisUtil.setIfAbsent(inflightKey, token, drawInflightRedisTtlSeconds);
        } catch (Exception e) {
            // 抽奖防重标记不可用时直接中断主链路，避免 Redis 异常时放开重复抽奖。
            logger.error("claim draw inflight key failed, key:{}", inflightKey, e);
            throw new IllegalStateException("draw inflight guard unavailable", e);
        }
    }

    @Override
    public void release(Long activityId, String uId, String token) {
        String inflightKey = Constants.RedisKey.KEY_LOTTERY_DRAW_IN_FLIGHT(activityId, uId);
        try {
            boolean released = redisUtil.deleteIfValueEquals(inflightKey, token);
            if (!released) {
                logger.warn("release draw inflight key skipped (not owner or expired), key:{}", inflightKey);
            }
        } catch (Exception e) {
            logger.warn("release draw inflight key failed key:{}", inflightKey, e);
        }
    }
}
