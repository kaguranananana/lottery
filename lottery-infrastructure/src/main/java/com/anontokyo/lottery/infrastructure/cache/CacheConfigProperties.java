package com.anontokyo.lottery.infrastructure.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 缓存分层配置。
 * strategy-snapshot 使用 Caffeine + Redis 两级；
 * activity 配置为低频读、允许短暂滞后，只走 Redis 单级，避免多实例本地缓存不一致问题。
 */
@Component
@ConfigurationProperties(prefix = "lottery.cache")
public class CacheConfigProperties {

    /**
     * Redis TTL for no-consumed user take order cache.
     */
    private long noConsumedOrderRedisTtlSeconds = 1800L;

    /**
     * Redis TTL for invoice MQ processing lock.
     */
    private long invoiceMqProcessingRedisTtlSeconds = 300L;

    /**
     * Redis TTL for outbox dispatch marker.
     */
    private long stockMqDispatchRedisTtlSeconds = 60L;

    /** 抽奖策略快照：高频读、低变更，唯一使用两级缓存的实体 */
    private TwoLayerConfig strategySnapshot = new TwoLayerConfig(60L, 600L, 120L, 60L, 10000L);

    /** 活动配置：仅 Redis 单级 */
    private SingleLayerConfig activity = new SingleLayerConfig(300L, 60L, 30L);

    public long getNoConsumedOrderRedisTtlSeconds() {
        return noConsumedOrderRedisTtlSeconds;
    }

    public void setNoConsumedOrderRedisTtlSeconds(long noConsumedOrderRedisTtlSeconds) {
        this.noConsumedOrderRedisTtlSeconds = noConsumedOrderRedisTtlSeconds;
    }

    public long getInvoiceMqProcessingRedisTtlSeconds() {
        return invoiceMqProcessingRedisTtlSeconds;
    }

    public void setInvoiceMqProcessingRedisTtlSeconds(long invoiceMqProcessingRedisTtlSeconds) {
        this.invoiceMqProcessingRedisTtlSeconds = invoiceMqProcessingRedisTtlSeconds;
    }

    public long getStockMqDispatchRedisTtlSeconds() {
        return stockMqDispatchRedisTtlSeconds;
    }

    public void setStockMqDispatchRedisTtlSeconds(long stockMqDispatchRedisTtlSeconds) {
        this.stockMqDispatchRedisTtlSeconds = stockMqDispatchRedisTtlSeconds;
    }

    public TwoLayerConfig getStrategySnapshot() {
        return strategySnapshot;
    }

    public void setStrategySnapshot(TwoLayerConfig strategySnapshot) {
        this.strategySnapshot = strategySnapshot;
    }

    public SingleLayerConfig getActivity() {
        return activity;
    }

    public void setActivity(SingleLayerConfig activity) {
        this.activity = activity;
    }

    public static class TwoLayerConfig {
        private long localExpireSeconds;
        private long localMaxSize;
        private long redisExpireSeconds;
        private long redisExpireJitterSeconds;
        private long nullRedisExpireSeconds;

        public TwoLayerConfig() {
        }

        public TwoLayerConfig(long localExpireSeconds, long localMaxSize,
                long redisExpireSeconds, long redisExpireJitterSeconds, long nullRedisExpireSeconds) {
            this.localExpireSeconds = localExpireSeconds;
            this.localMaxSize = localMaxSize;
            this.redisExpireSeconds = redisExpireSeconds;
            this.redisExpireJitterSeconds = redisExpireJitterSeconds;
            this.nullRedisExpireSeconds = nullRedisExpireSeconds;
        }

        public long getLocalExpireSeconds() {
            return localExpireSeconds;
        }

        public void setLocalExpireSeconds(long localExpireSeconds) {
            this.localExpireSeconds = localExpireSeconds;
        }

        public long getLocalMaxSize() {
            return localMaxSize;
        }

        public void setLocalMaxSize(long localMaxSize) {
            this.localMaxSize = localMaxSize;
        }

        public long getRedisExpireSeconds() {
            return redisExpireSeconds;
        }

        public void setRedisExpireSeconds(long redisExpireSeconds) {
            this.redisExpireSeconds = redisExpireSeconds;
        }

        public long getRedisExpireJitterSeconds() {
            return redisExpireJitterSeconds;
        }

        public void setRedisExpireJitterSeconds(long redisExpireJitterSeconds) {
            this.redisExpireJitterSeconds = redisExpireJitterSeconds;
        }

        public long getNullRedisExpireSeconds() {
            return nullRedisExpireSeconds;
        }

        public void setNullRedisExpireSeconds(long nullRedisExpireSeconds) {
            this.nullRedisExpireSeconds = nullRedisExpireSeconds;
        }
    }

    public static class SingleLayerConfig {
        private long redisExpireSeconds;
        private long redisExpireJitterSeconds;
        private long nullRedisExpireSeconds;

        public SingleLayerConfig() {
        }

        public SingleLayerConfig(long redisExpireSeconds, long redisExpireJitterSeconds,
                long nullRedisExpireSeconds) {
            this.redisExpireSeconds = redisExpireSeconds;
            this.redisExpireJitterSeconds = redisExpireJitterSeconds;
            this.nullRedisExpireSeconds = nullRedisExpireSeconds;
        }

        public long getRedisExpireSeconds() {
            return redisExpireSeconds;
        }

        public void setRedisExpireSeconds(long redisExpireSeconds) {
            this.redisExpireSeconds = redisExpireSeconds;
        }

        public long getRedisExpireJitterSeconds() {
            return redisExpireJitterSeconds;
        }

        public void setRedisExpireJitterSeconds(long redisExpireJitterSeconds) {
            this.redisExpireJitterSeconds = redisExpireJitterSeconds;
        }

        public long getNullRedisExpireSeconds() {
            return nullRedisExpireSeconds;
        }

        public void setNullRedisExpireSeconds(long nullRedisExpireSeconds) {
            this.nullRedisExpireSeconds = nullRedisExpireSeconds;
        }
    }
}
