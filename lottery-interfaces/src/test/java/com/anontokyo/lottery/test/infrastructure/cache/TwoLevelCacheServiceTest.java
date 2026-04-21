package com.anontokyo.lottery.test.infrastructure.cache;

import com.anontokyo.lottery.infrastructure.cache.CacheConfigProperties;
import com.anontokyo.lottery.infrastructure.cache.TwoLevelCacheService;
import com.anontokyo.lottery.infrastructure.po.Activity;
import com.anontokyo.lottery.infrastructure.utils.RedisUtil;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TwoLevelCacheServiceTest {

    @Test
    public void shouldWriteNullPlaceholderWhenSourceValueMissing() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get("lottery:cache:activity:missing")).thenReturn(null);
        CacheConfigProperties cacheConfigProperties = buildCacheConfigProperties();
        TwoLevelCacheService cacheService = buildCacheService(redisUtil, cacheConfigProperties);

        AtomicInteger loaderCalls = new AtomicInteger();
        Activity activity = cacheService.getActivityCache(
                "lottery:cache:activity:missing",
                Activity.class,
                () -> {
                    loaderCalls.incrementAndGet();
                    return null;
                });

        assertNull(activity);
        assertEquals(1, loaderCalls.get());
        verify(redisUtil).setWithExpire("lottery:cache:activity:missing", "__NULL__", 15L);
    }

    @Test
    public void shouldReturnNullWithoutLoadingSourceWhenNullPlaceholderExists() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get("lottery:cache:activity:null")).thenReturn("__NULL__");
        TwoLevelCacheService cacheService = buildCacheService(redisUtil, buildCacheConfigProperties());

        AtomicInteger loaderCalls = new AtomicInteger();
        Activity activity = cacheService.getActivityCache(
                "lottery:cache:activity:null",
                Activity.class,
                () -> {
                    loaderCalls.incrementAndGet();
                    return new Activity();
                });

        assertNull(activity);
        assertEquals(0, loaderCalls.get());
    }

    @Test
    public void shouldUseRedisTtlJitterWhenWritingSourceValue() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get("lottery:cache:activity:100001")).thenReturn(null);
        CacheConfigProperties cacheConfigProperties = buildCacheConfigProperties();
        cacheConfigProperties.getActivity().setRedisExpireJitterSeconds(20L);
        TwoLevelCacheService cacheService = buildCacheService(redisUtil, cacheConfigProperties);

        Activity source = new Activity();
        source.setActivityId(100001L);
        Activity activity = cacheService.getActivityCache(
                "lottery:cache:activity:100001",
                Activity.class,
                () -> source);

        assertNotNull(activity);
        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(redisUtil).setWithExpire(eq("lottery:cache:activity:100001"), anyString(), ttlCaptor.capture());
        long actualTtl = ttlCaptor.getValue();
        assertTrue(actualTtl >= 60L);
        assertTrue(actualTtl <= 80L);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldExposeRedisReadFailure() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get("lottery:cache:activity:redis-error"))
                .thenThrow(new RuntimeException("redis read failed"));
        TwoLevelCacheService cacheService = buildCacheService(redisUtil, buildCacheConfigProperties());

        cacheService.getActivityCache(
                "lottery:cache:activity:redis-error",
                Activity.class,
                () -> {
                    Activity activity = new Activity();
                    activity.setActivityId(100001L);
                    return activity;
                });
    }

    @Test(expected = IllegalStateException.class)
    public void shouldExposeRedisWriteFailure() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.get("lottery:cache:activity:write-error")).thenReturn(null);
        org.mockito.Mockito.doThrow(new RuntimeException("redis write failed"))
                .when(redisUtil).setWithExpire(eq("lottery:cache:activity:write-error"), anyString(), eq(60L));
        TwoLevelCacheService cacheService = buildCacheService(redisUtil, buildCacheConfigProperties());

        cacheService.getActivityCache(
                "lottery:cache:activity:write-error",
                Activity.class,
                () -> {
                    Activity activity = new Activity();
                    activity.setActivityId(100001L);
                    return activity;
                });
    }

    @Test
    public void shouldLoadSourceOnlyOnceWhenSameKeyMissesConcurrently() throws Exception {
        InMemoryRedisUtil redisUtil = new InMemoryRedisUtil();
        TwoLevelCacheService cacheService = buildCacheService(redisUtil, buildCacheConfigProperties());
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger loaderCalls = new AtomicInteger();

        try {
            Future<Activity> first = executorService.submit(() -> loadActivityConcurrently(cacheService, barrier, loaderCalls));
            Future<Activity> second = executorService.submit(() -> loadActivityConcurrently(cacheService, barrier, loaderCalls));

            assertEquals(Long.valueOf(100001L), first.get(3, TimeUnit.SECONDS).getActivityId());
            assertEquals(Long.valueOf(100001L), second.get(3, TimeUnit.SECONDS).getActivityId());
            assertEquals(1, loaderCalls.get());
        } finally {
            executorService.shutdownNow();
        }
    }

    private Activity loadActivityConcurrently(TwoLevelCacheService cacheService, CyclicBarrier barrier,
            AtomicInteger loaderCalls) throws Exception {
        barrier.await(1, TimeUnit.SECONDS);
        return cacheService.getActivityCache(
                "lottery:cache:activity:concurrent",
                Activity.class,
                () -> {
                    loaderCalls.incrementAndGet();
                    sleepQuietly(120L);
                    Activity activity = new Activity();
                    activity.setActivityId(100001L);
                    return activity;
                });
    }

    private TwoLevelCacheService buildCacheService(RedisUtil redisUtil, CacheConfigProperties cacheConfigProperties) {
        TwoLevelCacheService cacheService = new TwoLevelCacheService();
        ReflectionTestUtils.setField(cacheService, "redisUtil", redisUtil);
        ReflectionTestUtils.setField(cacheService, "cacheConfigProperties", cacheConfigProperties);
        cacheService.init();
        return cacheService;
    }

    private CacheConfigProperties buildCacheConfigProperties() {
        CacheConfigProperties cacheConfigProperties = new CacheConfigProperties();
        cacheConfigProperties.setActivity(new CacheConfigProperties.LayerCacheConfig(30L, 60L, 1000L));
        cacheConfigProperties.getActivity().setNullRedisExpireSeconds(15L);
        return cacheConfigProperties;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class InMemoryRedisUtil extends RedisUtil {
        private final Map<String, String> values = new ConcurrentHashMap<>();

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void setWithExpire(String key, String value, long expireSeconds) {
            values.put(key, value);
        }

        @Override
        public void delStringKey(String key) {
            values.remove(key);
        }
    }
}
