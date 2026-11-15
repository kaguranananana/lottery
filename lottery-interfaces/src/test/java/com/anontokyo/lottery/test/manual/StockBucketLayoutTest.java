package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.infrastructure.repository.StockBucketLayout;

import com.anontokyo.lottery.infrastructure.po.AwardStockBucket;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class StockBucketLayoutTest {
    @Test public void distributesAllStockIncludingNonDivisibleAndSmallCounts() {
        List<AwardStockBucket> result = StockBucketLayout.allocate(10L, "a", 3, 8);
        assertEquals(8, result.size());
        assertEquals(3, result.stream().mapToInt(AwardStockBucket::getInitialCount).sum());
        assertEquals(3L, result.stream().filter(b -> b.getInitialCount() == 1).count());
        assertEquals(5L, result.stream().filter(b -> b.getInitialCount() == 0).count());
    }
    @Test public void mapsNearbyIdsAcrossMultipleBucketsDeterministically() {
        boolean[] seen = new boolean[8];
        for (long id = 100_000L; id < 101_000L; id++) {
            int bucket = StockBucketLayout.firstBucket(id << 16, 8);
            assertEquals(bucket, StockBucketLayout.firstBucket(id << 16, 8));
            seen[bucket] = true;
        }
        for (boolean value : seen) assertTrue("all buckets should be reachable", value);
    }
    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidBucketCount() { StockBucketLayout.allocate(1L, "a", 10, 0); }
}
