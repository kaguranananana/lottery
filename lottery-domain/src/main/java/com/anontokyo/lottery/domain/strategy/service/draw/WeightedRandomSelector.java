package com.anontokyo.lottery.domain.strategy.service.draw;

import com.anontokyo.lottery.domain.strategy.model.snapshot.StrategySnapshot;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 加权随机选择器：对快照的整数权重前缀和做二分采样。
 * NO_PRIZE 对应 awardIds 中为 null 的槽位（策略概率总和不足 100% 时由快照编译阶段预留）。
 */
public final class WeightedRandomSelector {

    private WeightedRandomSelector() {
    }

    /**
     * @return 命中的奖品ID；返回 null 表示未中奖（NO_PRIZE）
     */
    public static String select(StrategySnapshot snapshot) {
        return selectAt(snapshot, ThreadLocalRandom.current().nextLong(snapshot.totalWeight()) + 1L);
    }

    /**
     * 确定性采样：samplePoint ∈ [1, totalWeight]，边界可见便于测试与复核。
     */
    public static String selectAt(StrategySnapshot snapshot, long samplePoint) {
        long[] prefix = snapshot.getCumulativeWeights();
        int lo = 0;
        int hi = prefix.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (prefix[mid] >= samplePoint) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return snapshot.getAwardIds()[lo];
    }
}
