package com.anontokyo.lottery.interfaces.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;

/** 抽奖 HTTP 入口的本机并发和共享 Redis 配额。 */
@Component
public class DrawAdmission {

    private static final String KEY_PREFIX = "lottery:{draw-admission}:";
    private static final RedisScript<Long> BUCKETS =
            RedisScript.of(new ClassPathResource("redis/draw-admission.lua"), Long.class);

    public enum Decision { ADMITTED, GLOBAL_LIMIT, USER_LIMIT }

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final Semaphore permits;
    private final String[] bucketSettings;
    private final LongAdder admitted = new LongAdder();
    private final LongAdder globalRejected = new LongAdder();
    private final LongAdder userRejected = new LongAdder();
    private final LongAdder concurrentRejected = new LongAdder();
    private final LongAdder redisCalls = new LongAdder();
    private final LongAdder redisNanos = new LongAdder();
    private final LongAdder redisErrors = new LongAdder();

    public DrawAdmission(StringRedisTemplate redis,
            @Value("${lottery.draw.admission.enabled:true}") boolean enabled,
            @Value("${lottery.draw.admission.max-concurrent:24}") int maxConcurrent,
            @Value("${lottery.draw.admission.global-rate:100}") int globalRate,
            @Value("${lottery.draw.admission.global-burst:32}") int globalBurst,
            @Value("${lottery.draw.admission.user-rate:2}") int userRate,
            @Value("${lottery.draw.admission.user-burst:3}") int userBurst) {
        if (maxConcurrent <= 0 || globalRate <= 0 || globalBurst <= 0 || userRate <= 0 || userBurst <= 0) {
            throw new IllegalArgumentException("抽奖限流的速率、突发容量和并发上限必须为正数");
        }
        this.redis = redis;
        this.enabled = enabled;
        permits = new Semaphore(maxConcurrent);
        bucketSettings = new String[]{String.valueOf(globalRate), String.valueOf(globalBurst),
                String.valueOf(userRate), String.valueOf(userBurst)};
    }

    public boolean tryAcquire() {
        // 先保护实例线程和 Redis 客户端。本机拒绝不会消耗共享配额。
        if (!enabled || permits.tryAcquire()) {
            return true;
        }
        concurrentRejected.increment();
        return false;
    }

    public void release() {
        if (enabled) {
            permits.release();
        }
    }

    public Decision checkBuckets(String userId, long activityId) {
        if (!enabled) {
            admitted.increment();
            return Decision.ADMITTED;
        }

        long start = System.nanoTime();
        redisCalls.increment();
        try {
            // 两个键共用同一 Redis slot。用户 ID 来自服务端认证身份。
            Long result = redis.execute(BUCKETS, Arrays.asList(KEY_PREFIX + "global",
                    KEY_PREFIX + "user:" + activityId + ":" + userId), (Object[]) bucketSettings);
            if (Long.valueOf(0).equals(result)) {
                admitted.increment();
                return Decision.ADMITTED;
            }
            if (Long.valueOf(1).equals(result)) {
                globalRejected.increment();
                return Decision.GLOBAL_LIMIT;
            }
            if (Long.valueOf(2).equals(result)) {
                userRejected.increment();
                return Decision.USER_LIMIT;
            }
            throw new IllegalStateException("未知的 Redis 限流结果 " + result);
        } catch (RuntimeException e) {
            redisErrors.increment();
            throw e;
        } finally {
            redisNanos.add(System.nanoTime() - start);
        }
    }

    /** 累计采样计数，区分三个拒绝入口及 Redis 故障。 */
    public Map<String, Long> metrics() {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("admitted", admitted.sum());
        values.put("globalRejected", globalRejected.sum());
        values.put("userRejected", userRejected.sum());
        values.put("concurrentRejected", concurrentRejected.sum());
        values.put("redisCalls", redisCalls.sum());
        values.put("redisNanos", redisNanos.sum());
        values.put("redisErrors", redisErrors.sum());
        return values;
    }
}
