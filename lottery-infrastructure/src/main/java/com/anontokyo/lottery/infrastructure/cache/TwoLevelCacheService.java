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
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Two-level cache helper: local Caffeine + Redis.
 */
@Component
public class TwoLevelCacheService {

    private Logger logger = LoggerFactory.getLogger(TwoLevelCacheService.class);

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private CacheConfigProperties cacheConfigProperties;

    private Cache<String, Object> activityLocalCache;
    private Cache<String, Object> strategyRichLocalCache;
    private Cache<String, Object> awardLocalCache;
    private Cache<String, Object> ruleTreeLocalCache;

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
        return getWithFallback(activityLocalCache, cacheKey, cacheConfigProperties.getActivity().getRedisExpireSeconds(),
                clazz, loader);
    }

    public <T> T getStrategyRichCache(String cacheKey, Class<T> clazz, Supplier<T> loader) {
        return getWithFallback(strategyRichLocalCache, cacheKey,
                cacheConfigProperties.getStrategyRich().getRedisExpireSeconds(), clazz, loader);
    }

    public <T> T getAwardCache(String cacheKey, Class<T> clazz, Supplier<T> loader) {
        return getWithFallback(awardLocalCache, cacheKey, cacheConfigProperties.getAward().getRedisExpireSeconds(), clazz,
                loader);
    }

    public <T> T getRuleTreeCache(String cacheKey, Class<T> clazz, Supplier<T> loader) {
        return getWithFallback(ruleTreeLocalCache, cacheKey, cacheConfigProperties.getRuleTree().getRedisExpireSeconds(),
                clazz, loader);
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

    private <T> T getWithFallback(Cache<String, Object> localCache, String cacheKey, long redisExpireSeconds,
            Class<T> clazz, Supplier<T> loader) {
        T localValue = getLocalValue(localCache, cacheKey, clazz);
        if (localValue != null) {
            return localValue;
        }

        T redisValue = getRedisValue(cacheKey, clazz);
        if (redisValue != null) {
            putLocalValue(localCache, cacheKey, redisValue);
            return redisValue;
        }

        T sourceValue = loader.get();
        if (sourceValue == null) {
            return null;
        }

        putLocalValue(localCache, cacheKey, sourceValue);
        try {
            redisUtil.setWithExpire(cacheKey, JSON.toJSONString(sourceValue), redisExpireSeconds);
        } catch (Exception e) {
            logger.warn("write redis cache failed, key:{}", cacheKey, e);
        }
        return sourceValue;
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

    private <T> T getRedisValue(String cacheKey, Class<T> clazz) {
        try {
            String cacheValue = redisUtil.get(cacheKey);
            if (cacheValue == null || cacheValue.length() == 0) {
                return null;
            }
            T value = JSON.parseObject(cacheValue, clazz);
            if (value == null) {
                redisUtil.delStringKey(cacheKey);
            }
            return value;
        } catch (Exception e) {
            logger.warn("read redis cache failed, key:{}", cacheKey, e);
            return null;
        }
    }

    private void putLocalValue(Cache<String, Object> localCache, String cacheKey, Object value) {
        try {
            localCache.put(cacheKey, value);
        } catch (Exception e) {
            logger.warn("write local cache failed, key:{}", cacheKey, e);
        }
    }
}
