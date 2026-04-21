package com.anontokyo.lottery.infrastructure.cache;

import com.alibaba.fastjson.JSON;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.anontokyo.lottery.infrastructure.utils.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Two-level cache helper: local Caffeine + Redis.
 */
@Component
public class TwoLevelCacheService {

    private static final String NULL_VALUE = "__NULL__";

    private Logger logger = LoggerFactory.getLogger(TwoLevelCacheService.class);

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private CacheConfigProperties cacheConfigProperties;

    private Cache<String, Object> activityLocalCache;
    private Cache<String, Object> strategyRichLocalCache;
    private Cache<String, Object> awardLocalCache;
    private Cache<String, Object> ruleTreeLocalCache;
    private final Map<String, Object> rebuildLockMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        activityLocalCache = buildLocalCache(cacheConfigProperties.getActivity());
        strategyRichLocalCache = buildLocalCache(cacheConfigProperties.getStrategyRich());
        awardLocalCache = buildLocalCache(cacheConfigProperties.getAward());
        ruleTreeLocalCache = buildLocalCache(cacheConfigProperties.getRuleTree());
    }

    private Cache<String, Object> buildLocalCache(CacheConfigProperties.LayerCacheConfig config) {
        return Caffeine.newBuilder()
                .expireAfterWrite(config.getLocalExpireSeconds(), TimeUnit.SECONDS)
                .maximumSize(config.getLocalMaxSize())
                .build();
    }

    public <T> T getActivityCache(String cacheKey, Class<T> clazz, Supplier<T> loader) {
        return getWithCacheAside(activityLocalCache, cacheKey, cacheConfigProperties.getActivity(), clazz, loader);
    }

    public <T> T getStrategyRichCache(String cacheKey, Class<T> clazz, Supplier<T> loader) {
        return getWithCacheAside(strategyRichLocalCache, cacheKey, cacheConfigProperties.getStrategyRich(), clazz,
                loader);
    }

    public <T> T getAwardCache(String cacheKey, Class<T> clazz, Supplier<T> loader) {
        return getWithCacheAside(awardLocalCache, cacheKey, cacheConfigProperties.getAward(), clazz, loader);
    }

    public <T> T getRuleTreeCache(String cacheKey, Class<T> clazz, Supplier<T> loader) {
        return getWithCacheAside(ruleTreeLocalCache, cacheKey, cacheConfigProperties.getRuleTree(), clazz, loader);
    }

    public void evictActivityCache(String cacheKey) {
        evict(activityLocalCache, cacheKey);
    }

    public void evictStrategyRichCache(String cacheKey) {
        evict(strategyRichLocalCache, cacheKey);
    }

    public void evictAwardCache(String cacheKey) {
        evict(awardLocalCache, cacheKey);
    }

    private void evict(Cache<String, Object> localCache, String cacheKey) {
        if (cacheKey == null) {
            return;
        }
        try {
            localCache.invalidate(cacheKey);
        } catch (Exception e) {
            logger.warn("invalidate local cache failed, key:{}", cacheKey, e);
        }
        try {
            redisUtil.delStringKey(cacheKey);
        } catch (Exception e) {
            logger.warn("invalidate redis cache failed, key:{}", cacheKey, e);
        }
    }

    private <T> T getWithCacheAside(Cache<String, Object> localCache, String cacheKey,
            CacheConfigProperties.LayerCacheConfig layerCacheConfig,
            Class<T> clazz, Supplier<T> loader) {
        T localValue = getLocalValue(localCache, cacheKey, clazz);
        if (localValue != null) {
            return localValue;
        }

        RedisValue<T> redisValue = getRedisValue(cacheKey, clazz);
        if (redisValue.isHit()) {
            putLocalValue(localCache, cacheKey, redisValue.getValue());
            return redisValue.getValue();
        }
        if (redisValue.isNullPlaceholder()) {
            return null;
        }

        Object rebuildLock = rebuildLockMap.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (rebuildLock) {
            // 加锁后先双检本地缓存，避免等待锁期间其他线程已经完成回填。
            localValue = getLocalValue(localCache, cacheKey, clazz);
            if (localValue != null) {
                return localValue;
            }

            // 再双检 Redis，确保同 key 并发 miss 时只有一个线程真正回源。
            redisValue = getRedisValue(cacheKey, clazz);
            if (redisValue.isHit()) {
                putLocalValue(localCache, cacheKey, redisValue.getValue());
                return redisValue.getValue();
            }
            if (redisValue.isNullPlaceholder()) {
                return null;
            }

            T sourceValue = loader.get();
            if (sourceValue == null) {
                putNullValue(cacheKey, layerCacheConfig.getNullRedisExpireSeconds());
                return null;
            }

            putLocalValue(localCache, cacheKey, sourceValue);
            putRedisValue(cacheKey, sourceValue, nextRedisExpireSeconds(layerCacheConfig));
            return sourceValue;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T getLocalValue(Cache<String, Object> localCache, String cacheKey, Class<T> clazz) {
        try {
            Object localValue = localCache.getIfPresent(cacheKey);
            if (localValue == null) {
                return null;
            }
            if (clazz.isInstance(localValue)) {
                return (T) localValue;
            }
            localCache.invalidate(cacheKey);
        } catch (Exception e) {
            logger.warn("read local cache failed, key:{}", cacheKey, e);
        }
        return null;
    }

    private <T> RedisValue<T> getRedisValue(String cacheKey, Class<T> clazz) {
        try {
            String cacheValue = redisUtil.get(cacheKey);
            if (cacheValue == null || cacheValue.length() == 0) {
                return RedisValue.miss();
            }
            if (NULL_VALUE.equals(cacheValue)) {
                return RedisValue.nullPlaceholder();
            }
            T value = JSON.parseObject(cacheValue, clazz);
            if (value == null) {
                redisUtil.delStringKey(cacheKey);
                return RedisValue.miss();
            }
            return RedisValue.hit(value);
        } catch (Exception e) {
            logger.error("read redis cache failed, key:{}", cacheKey, e);
            throw new IllegalStateException("read redis cache failed, key:" + cacheKey, e);
        }
    }

    private void putRedisValue(String cacheKey, Object value, long redisExpireSeconds) {
        try {
            redisUtil.setWithExpire(cacheKey, JSON.toJSONString(value), redisExpireSeconds);
        } catch (Exception e) {
            logger.error("write redis cache failed, key:{}", cacheKey, e);
            throw new IllegalStateException("write redis cache failed, key:" + cacheKey, e);
        }
    }

    private void putNullValue(String cacheKey, long nullRedisExpireSeconds) {
        try {
            redisUtil.setWithExpire(cacheKey, NULL_VALUE, nullRedisExpireSeconds);
        } catch (Exception e) {
            logger.error("write redis null cache failed, key:{}", cacheKey, e);
            throw new IllegalStateException("write redis null cache failed, key:" + cacheKey, e);
        }
    }

    private void putLocalValue(Cache<String, Object> localCache, String cacheKey, Object value) {
        try {
            localCache.put(cacheKey, value);
        } catch (Exception e) {
            logger.warn("write local cache failed, key:{}", cacheKey, e);
        }
    }

    private long nextRedisExpireSeconds(CacheConfigProperties.LayerCacheConfig layerCacheConfig) {
        long baseExpireSeconds = layerCacheConfig.getRedisExpireSeconds();
        long jitterSeconds = layerCacheConfig.getRedisExpireJitterSeconds();
        if (jitterSeconds <= 0) {
            return baseExpireSeconds;
        }
        return baseExpireSeconds + ThreadLocalRandom.current().nextLong(jitterSeconds + 1L);
    }

    private static class RedisValue<T> {
        private final boolean hit;
        private final boolean nullPlaceholder;
        private final T value;

        private RedisValue(boolean hit, boolean nullPlaceholder, T value) {
            this.hit = hit;
            this.nullPlaceholder = nullPlaceholder;
            this.value = value;
        }

        private static <T> RedisValue<T> hit(T value) {
            return new RedisValue<>(true, false, value);
        }

        private static <T> RedisValue<T> nullPlaceholder() {
            return new RedisValue<>(false, true, null);
        }

        private static <T> RedisValue<T> miss() {
            return new RedisValue<>(false, false, null);
        }

        private boolean isHit() {
            return hit;
        }

        private boolean isNullPlaceholder() {
            return nullPlaceholder;
        }

        private T getValue() {
            return value;
        }
    }
}
