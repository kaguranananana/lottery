package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.infrastructure.po.AwardStockBucket;
import java.util.ArrayList;
import java.util.List;

/** 库存桶初始化与确定性分布；不涉及外部状态。 */
public final class StockBucketLayout {
    private StockBucketLayout() { }

    public static List<AwardStockBucket> allocate(Long activityId, String awardId, int total, int buckets) {
        if (total < 0 || buckets < 1 || buckets > 64) {
            throw new IllegalArgumentException("invalid bucket layout total=" + total + " buckets=" + buckets);
        }
        List<AwardStockBucket> result = new ArrayList<>(buckets);
        int base = total / buckets;
        int remainder = total % buckets;
        for (int i = 0; i < buckets; i++) {
            result.add(new AwardStockBucket(activityId, awardId, i, base + (i < remainder ? 1 : 0)));
        }
        return result;
    }

    /** 混合 Snowflake 顺序 ID，避免对小桶数简单取模导致热点集中。 */
    public static int firstBucket(long orderId, int buckets) {
        if (buckets < 1 || buckets > 64) {
            throw new IllegalArgumentException("invalid buckets=" + buckets);
        }
        long value = orderId;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return (int) Long.remainderUnsigned(value, buckets);
    }
}
