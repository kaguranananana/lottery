package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.interfaces.web.DrawAdmission;
import com.anontokyo.lottery.interfaces.web.DrawAdmission.Decision;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** 用独立 Redis 真实验证 Lua 与跨实例共享配额。 */
public class DrawAdmissionRedisManualIT {

    private LettuceConnectionFactory connections;
    private StringRedisTemplate redis;

    @Before
    public void connect() {
        assertEquals("16379", System.getProperty("lottery.it.redis.port"));
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("127.0.0.1", 16379);
        // 测试桶使用单独的 Redis 逻辑库，避免清除其它测试的全局桶。
        config.setDatabase(12);
        connections = new LettuceConnectionFactory(config);
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
        redis.delete("lottery:{draw-admission}:global");
    }

    @After
    public void close() {
        if (connections != null) {
            connections.destroy();
        }
    }

    @Test
    public void refillAndRejectionsDoNotSpendTheOtherBucket() throws Exception {
        long activity = System.nanoTime();
        DrawAdmission first = new DrawAdmission(redis, true, 3, 1, 3, 1, 1);
        DrawAdmission second = new DrawAdmission(redis, true, 3, 1, 3, 1, 1);
        // 先建连接，避免首次握手时间被误算为令牌补充。
        redis.hasKey("lottery:{draw-admission}:global");
        assertEquals(Decision.ADMITTED, first.checkBuckets("A", activity));
        assertEquals(Decision.USER_LIMIT, second.checkBuckets("A", activity));
        assertEquals(Decision.ADMITTED, second.checkBuckets("A", activity + 1));
        assertEquals(Decision.ADMITTED, first.checkBuckets("B", activity));
        assertEquals(Decision.GLOBAL_LIMIT, second.checkBuckets("C", activity));
        // 全局拒绝无须写入用户桶，证明 C 未被扣费。
        assertFalse(redis.hasKey("lottery:{draw-admission}:user:" + activity + ":C"));
        Thread.sleep(1100);
        assertEquals(Decision.ADMITTED, second.checkBuckets("C", activity));
    }

    @Test
    public void concurrentClientsShareOneGlobalBurst() throws Exception {
        DrawAdmission first = new DrawAdmission(redis, true, 20, 1, 5, 1, 1);
        DrawAdmission second = new DrawAdmission(redis, true, 20, 1, 5, 1, 1);
        long activity = System.nanoTime();
        ExecutorService threads = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Decision>> results = new ArrayList<>();
        try {
            // 不同用户跨两个服务实例并发争夺同一全局桶。
            for (int i = 0; i < 20; i++) {
                final int user = i;
                results.add(threads.submit(() -> {
                    start.await();
                    return (user % 2 == 0 ? first : second).checkBuckets("user" + user, activity);
                }));
            }
            start.countDown();
            int allowed = 0;
            for (Future<Decision> result : results) {
                Decision decision = result.get(2, TimeUnit.SECONDS);
                if (decision == Decision.ADMITTED) {
                    allowed++;
                } else {
                    assertEquals(Decision.GLOBAL_LIMIT, decision);
                }
            }
            assertEquals(5, allowed);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    public void expiredDrawOwnerCannotDeleteNewRedisMarker() throws Exception {
        com.anontokyo.lottery.infrastructure.utils.RedisUtil commands =
                new com.anontokyo.lottery.infrastructure.utils.RedisUtil();
        org.springframework.test.util.ReflectionTestUtils.setField(commands, "stringRedisTemplate", redis);
        com.anontokyo.lottery.infrastructure.lock.RedisActivityDrawProcessGuard guard =
                new com.anontokyo.lottery.infrastructure.lock.RedisActivityDrawProcessGuard();
        org.springframework.test.util.ReflectionTestUtils.setField(guard, "redisUtil", commands);
        org.springframework.test.util.ReflectionTestUtils.setField(guard, "drawInflightRedisTtlSeconds", 1L);
        long activity = System.nanoTime();
        assertTrue(guard.tryClaim(activity, "ttl-probe", "old-owner"));
        Thread.sleep(1100);
        assertTrue(guard.tryClaim(activity, "ttl-probe", "new-owner"));
        guard.release(activity, "ttl-probe", "old-owner");
        assertFalse(guard.tryClaim(activity, "ttl-probe", "third-owner"));
        guard.release(activity, "ttl-probe", "new-owner");
        assertTrue(guard.tryClaim(activity, "ttl-probe", "third-owner"));
        guard.release(activity, "ttl-probe", "third-owner");
    }

    @Test
    public void localPermitIsIndependentFromGlobalAndUserBudgets() {
        DrawAdmission admission = new DrawAdmission(redis, true, 1, 100, 20, 10, 10);
        assertTrue(admission.tryAcquire());
        assertFalse(admission.tryAcquire());
        assertEquals(Long.valueOf(0), admission.metrics().get("redisCalls"));
        admission.release();
        assertTrue(admission.tryAcquire());
        admission.release();
        assertEquals(Long.valueOf(1), admission.metrics().get("concurrentRejected"));
    }
}
