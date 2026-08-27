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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 缓存基础设施：
 *  - 两级缓存（Caffeine L1 + Redis L2）只用于 StrategySnapshot：高频读、低变更、每次抽奖必需；
 *  - 其余配置类数据（activity 等）走 {@link #getRedisCache} 单级 Redis，避免为多实例本地缓存失效引入广播机制；
 *  - null 占位防止不存在配置的反复穿透。
 * 一致性边界：快照视为短 TTL 的最终一致读（TTL 内可能读到旧版本）；运营变更需等 TTL 过期生效。
 */
@Component
public class TwoLevelCacheService {

    private static final String NULL_VALUE = "__NULL__";

    private Logger logger = LoggerFactory.getLogger(TwoLevelCacheService.class);

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private CacheConfigProperties cacheConfigProperties;

    private Cache<String, Object> snapshotLocalCache;

    @PostConstruct
    public void init() {
        CacheConfigProperties.TwoLayerConfig config = cacheConfigProperties.getStrategySnapshot();
        snapshotLocalCache = Caffeine.newBuilder()
                .expireAfterWrite(config.getLocalExpireSeconds(), TimeUnit.SECONDS)
                .maximumSize(config.getLocalMaxSize())
                .build();
    }

    /**
     * 两级缓存读取（仅 StrategySnapshot 使用）。
     */
    public <T> T getStrategySnapshotCache(String cacheKey, Class<T> clazz, Supplier<T> loader) {
        CacheConfigProperties.TwoLayerConfig config = cacheConfigProperties.getStrategySnapshot();

        T localValue = getLocalValue(snapshotLocalCache, cacheKey, clazz);
        if (localValue != null) {
            return localValue;
        }

        RedisValue<T> redisValue = getRedisValue(cacheKey, clazz);
        if (redisValue.isHit()) {
            putLocalValue(snapshotLocalCache, cacheKey, redisValue.getValue());
            return redisValue.getValue();
        }
        if (redisValue.isNullPlaceholder()) {
            return null;
        }

        synchronized (this) {
            localValue = getLocalValue(snapshotLocalCache, cacheKey, clazz);
            if (localValue != null) {
                return localValue;
            }
            redisValue = getRedisValue(cacheKey, clazz);
            if (redisValue.isHit()) {
                putLocalValue(snapshotLocalCache, cacheKey, redisValue.getValue());
                return redisValue.getValue();
            }
            if (redisValue.isNullPlaceholder()) {
                return null;
            }

            T sourceValue = loader.get();
            if (sourceValue == null) {
                putNullPlaceholder(cacheKey, config.getNullRedisExpireSeconds());
                return null;
            }
            putLocalValue(snapshotLocalCache, cacheKey, sourceValue);
            writeRedis(cacheKey, sourceValue, nextRedisExpireSeconds(
                    config.getRedisExpireSeconds(), config.getRedisExpireJitterSeconds()));
            return sourceValue;
        }
    }

    /**
     * Redis 单级缓存读取（活动等低频配置）。
     */
    public <T> T getRedisCache(String cacheKey, Class<T> clazz,
            CacheConfigProperties.SingleLayerConfig config, Supplier<T> loader) {
        RedisValue<T> redisValue = getRedisValue(cacheKey, clazz);
        if (redisValue.isHit()) {
            return redisValue.getValue();
        }
        if (redisValue.isNullPlaceholder()) {
            return null;
        }

        T sourceValue = loader.get();
        if (sourceValue == null) {
            putNullPlaceholder(cacheKey, config.getNullRedisExpireSeconds());
            return null;
        }
        writeRedis(cacheKey, sourceValue,
                nextRedisExpireSeconds(config.getRedisExpireSeconds(), config.getRedisExpireJitterSeconds()));
        return sourceValue;
    }

    public void evictStrategySnapshotCache(String cacheKey) {
        evict(cacheKey);
    }

    public void evictActivityCache(String cacheKey) {
        try {
            redisUtil.delStringKey(cacheKey);
        } catch (Exception e) {
            logger.warn("invalidate redis cache failed, key:{}", cacheKey, e);
        }
    }

    private void evict(String cacheKey) {
        if (cacheKey == null) {
            return;
        }
        try {
            snapshotLocalCache.invalidate(cacheKey);
        } catch (Exception e) {
            logger.warn("invalidate local cache failed, key:{}", cacheKey, e);
        }
        try {
            redisUtil.delStringKey(cacheKey);
        } catch (Exception e) {
            logger.warn("invalidate redis cache failed, key:{}", cacheKey, e);
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
    }

    private void writeRedis(String cacheKey, Object value, long ttlSeconds) {
        try {
            redisUtil.setWithExpire(cacheKey, JSON.toJSONString(value), ttlSeconds);
        } catch (Exception e) {
            // 回写失败不阻断主流程：下次 miss 会重试回源
            logger.error("write redis cache failed, key:{}", cacheKey, e);
        }
    }

    private void putNullPlaceholder(String cacheKey, long nullRedisExpireSeconds) {
        try {
            redisUtil.setWithExpire(cacheKey, NULL_VALUE, nullRedisExpireSeconds);
        } catch (Exception e) {
            logger.error("write redis null cache failed, key:{}", cacheKey, e);
        }
    }

    private void putLocalValue(Cache<String, Object> localCache, String cacheKey, Object value) {
        try {
            localCache.put(cacheKey, value);
        } catch (Exception e) {
            logger.warn("write local cache failed, key:{}", cacheKey, e);
        }
    }

    private long nextRedisExpireSeconds(long baseExpireSeconds, long jitterSeconds) {
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
