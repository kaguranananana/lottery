package com.anontokyo.lottery.test.domain;

import com.anontokyo.lottery.domain.strategy.model.aggregates.StrategyRich;
import com.anontokyo.lottery.domain.strategy.model.snapshot.StrategySnapshot;
import com.anontokyo.lottery.domain.strategy.model.vo.AwardBriefVO;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyBriefVO;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyDetailBriefVO;
import com.anontokyo.lottery.domain.strategy.service.draw.WeightedRandomSelector;
import com.anontokyo.lottery.domain.strategy.service.snapshot.StrategySnapshotFactory;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 抽奖概率编译与采样单元测试。
 * 覆盖：0.01%/0.1%/1%/10% 高精度概率不丢、概率总和不足 100% 的差额成为 NO_PRIZE、
 * 配置非法时显式失败、采样命中正确区间边界。
 */
public class WeightedRandomSelectorTest {

    private final StrategySnapshotFactory factory = new StrategySnapshotFactory();

    private StrategyRich rich(BigDecimal... rates) {
        StrategyBriefVO strategy = new StrategyBriefVO();
        strategy.setStrategyId(10001L);
        List<StrategyDetailBriefVO> details = new ArrayList<>(rates.length);
        int awardNo = 1;
        for (BigDecimal rate : rates) {
            StrategyDetailBriefVO detail = new StrategyDetailBriefVO();
            detail.setStrategyId(10001L);
            detail.setAwardId(String.valueOf(awardNo++));
            detail.setAwardRate(rate);
            details.add(detail);
        }
        return new StrategyRich(10001L, strategy, details);
    }

    private final StrategySnapshotFactory.AwardInfoResolver resolver = awardId -> {
        AwardBriefVO vo = new AwardBriefVO();
        vo.setAwardId(awardId);
        return vo;
    };

    @Test
    public void build_keepsMicroPrecisionWeights() {
        // 0.01% + 0.1% + 1% + 10% = 11.11%，其余 88.89% 为 NO_PRIZE
        StrategySnapshot snapshot = factory.build(
                rich(new BigDecimal("0.0001"), new BigDecimal("0.001"),
                        new BigDecimal("0.01"), new BigDecimal("0.1")),
                resolver);

        long[] prefix = snapshot.getCumulativeWeights();
        assertEquals(5, prefix.length); // 4 awards + NO_PRIZE
        assertEquals(100L, prefix[0]);           // 0.01% -> 100/1e6
        assertEquals(1100L, prefix[1]);          // 0.1%  -> 1000/1e6
        assertEquals(11100L, prefix[2]);         // 1%    -> 10000/1e6
        assertEquals(111100L, prefix[3]);        // 10%   -> 100000/1e6
        assertEquals(1_000_000L, prefix[4]);     // NO_PRIZE 补足到 100%
        assertNull(snapshot.getAwardIds()[4]);
    }

    @Test
    public void select_hitsCorrectBoundaries() {
        StrategySnapshot snapshot = factory.build(rich(new BigDecimal("0.5")), resolver);
        // total = 500_000，NO_PRIZE = 500_000

        assertEquals("1", WeightedRandomSelector.selectAt(snapshot, 1L));
        assertEquals("1", WeightedRandomSelector.selectAt(snapshot, 500_000L));
        assertNull(WeightedRandomSelector.selectAt(snapshot, 500_001L));
        assertNull(WeightedRandomSelector.selectAt(snapshot, 1_000_000L));
    }

    @Test
    public void sampleDistributionStaysWithinBounds() {
        // 30 个样本全部落在合法区间即可；分布统计不做精度断言（非本测试目的）
        StrategySnapshot snapshot = factory.build(
                rich(new BigDecimal("0.001"), new BigDecimal("0.05"), new BigDecimal("0.949")), resolver);
        for (long r = 1; r <= 1_000_000; r += 9973) {
            String awardId = WeightedRandomSelector.selectAt(snapshot, r);
            assertTrue(awardId == null || "1".equals(awardId) || "2".equals(awardId) || "3".equals(awardId));
        }
    }

    @Test
    public void zeroProbabilityAwardNeverSelected() {
        StrategySnapshot snapshot = factory.build(rich(BigDecimal.ZERO, new BigDecimal("0.4")), resolver);
        assertEquals(2, snapshot.getCumulativeWeights().length); // award2 + NO_PRIZE
        assertEquals("2", snapshot.getAwardIds()[0]);
        assertNull(snapshot.getAwardIds()[1]);
    }

    @Test
    public void overHundredPercentIsRejected() {
        try {
            factory.build(rich(new BigDecimal("0.6"), new BigDecimal("0.6")), resolver);
            fail("total probability > 100% must fail fast");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void negativeRateIsRejected() {
        try {
            factory.build(rich(new BigDecimal("-0.1")), resolver);
            fail("negative rate must fail fast");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void randomDrawNeverThrowsAndRespectsNullForFullNoPrize() {
        // 全 0 概率：只能未中奖
        StrategySnapshot snapshot = factory.build(rich(BigDecimal.ZERO, BigDecimal.ZERO), resolver);
        for (int i = 0; i < 1000; i++) {
            assertNull(WeightedRandomSelector.select(snapshot));
        }
    }
}
