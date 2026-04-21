package com.anontokyo.lottery.infrastructure.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cache config for two-level cache and no-consumed order cache.
 */
@Component
@ConfigurationProperties(prefix = "lottery.cache")
public class CacheConfigProperties {

    /**
     * Redis TTL for no-consumed user take order cache.
     */
    private long noConsumedOrderRedisTtlSeconds = 1800L;

    /**
     * Redis TTL for strategy no-stock award list cache.
     */
    private long noStockAwardListRedisTtlSeconds = 60L;

    /**
     * Redis TTL for strategy-award stock MQ dedup marker.
     */
    private long strategyAwardStockMqDedupRedisTtlSeconds = 86400L;

    /**
     * Redis TTL for invoice MQ processing lock.
     */
    private long invoiceMqProcessingRedisTtlSeconds = 300L;

    private LayerCacheConfig activity = new LayerCacheConfig(1L, 5L, 10000L);

    private LayerCacheConfig strategyRich = new LayerCacheConfig(30L, 600L, 10000L);

    private LayerCacheConfig award = new LayerCacheConfig(30L, 600L, 10000L);

    private LayerCacheConfig ruleTree = new LayerCacheConfig(30L, 1800L, 1000L);

    public long getNoConsumedOrderRedisTtlSeconds() {
        return noConsumedOrderRedisTtlSeconds;
    }

    public void setNoConsumedOrderRedisTtlSeconds(long noConsumedOrderRedisTtlSeconds) {
        this.noConsumedOrderRedisTtlSeconds = noConsumedOrderRedisTtlSeconds;
    }

    public long getNoStockAwardListRedisTtlSeconds() {
        return noStockAwardListRedisTtlSeconds;
    }

    public void setNoStockAwardListRedisTtlSeconds(long noStockAwardListRedisTtlSeconds) {
        this.noStockAwardListRedisTtlSeconds = noStockAwardListRedisTtlSeconds;
    }

    public long getStrategyAwardStockMqDedupRedisTtlSeconds() {
        return strategyAwardStockMqDedupRedisTtlSeconds;
    }

    public void setStrategyAwardStockMqDedupRedisTtlSeconds(long strategyAwardStockMqDedupRedisTtlSeconds) {
        this.strategyAwardStockMqDedupRedisTtlSeconds = strategyAwardStockMqDedupRedisTtlSeconds;
    }

    public long getInvoiceMqProcessingRedisTtlSeconds() {
        return invoiceMqProcessingRedisTtlSeconds;
    }

    public void setInvoiceMqProcessingRedisTtlSeconds(long invoiceMqProcessingRedisTtlSeconds) {
        this.invoiceMqProcessingRedisTtlSeconds = invoiceMqProcessingRedisTtlSeconds;
    }

    public LayerCacheConfig getActivity() {
        return activity;
    }

    public void setActivity(LayerCacheConfig activity) {
        this.activity = activity;
    }

    public LayerCacheConfig getStrategyRich() {
        return strategyRich;
    }

    public void setStrategyRich(LayerCacheConfig strategyRich) {
        this.strategyRich = strategyRich;
    }

    public LayerCacheConfig getAward() {
        return award;
    }

    public void setAward(LayerCacheConfig award) {
        this.award = award;
    }

    public LayerCacheConfig getRuleTree() {
        return ruleTree;
    }

    public void setRuleTree(LayerCacheConfig ruleTree) {
        this.ruleTree = ruleTree;
    }

    public static class LayerCacheConfig {
        private long localExpireSeconds = 30L;
        private long redisExpireSeconds = 600L;

        /**
         * Redis 正常缓存 TTL 抖动上限，单位秒，用于打散集中失效时间。
         */
        private long redisExpireJitterSeconds = 0L;

        /**
         * Redis 空值占位缓存 TTL，单位秒，用于拦截已确认不存在的配置查询。
         */
        private long nullRedisExpireSeconds = 60L;

        private long localMaxSize = 10000L;

        public LayerCacheConfig() {
        }

        public LayerCacheConfig(long localExpireSeconds, long redisExpireSeconds, long localMaxSize) {
            this.localExpireSeconds = localExpireSeconds;
            this.redisExpireSeconds = redisExpireSeconds;
            this.localMaxSize = localMaxSize;
        }

        public long getLocalExpireSeconds() {
            return localExpireSeconds;
        }

        public void setLocalExpireSeconds(long localExpireSeconds) {
            this.localExpireSeconds = localExpireSeconds;
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

        public long getLocalMaxSize() {
            return localMaxSize;
        }

        public void setLocalMaxSize(long localMaxSize) {
            this.localMaxSize = localMaxSize;
        }
    }
}
