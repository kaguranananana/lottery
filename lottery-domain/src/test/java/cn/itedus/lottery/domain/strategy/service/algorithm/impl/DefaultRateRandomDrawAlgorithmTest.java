package cn.itedus.lottery.domain.strategy.service.algorithm.impl;

import cn.itedus.lottery.domain.strategy.model.vo.AwardRateInfo;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class DefaultRateRandomDrawAlgorithmTest {

    private DefaultRateRandomDrawAlgorithm algorithm;
    private final Long strategyId = 10002L;

    @Before
    public void setUp() {
        algorithm = new DefaultRateRandomDrawAlgorithm();
        List<AwardRateInfo> awardRateInfos = new ArrayList<>();
        awardRateInfos.add(new AwardRateInfo("A100", new BigDecimal("0.30")));
        awardRateInfos.add(new AwardRateInfo("B200", new BigDecimal("0.70")));
        algorithm.initRateTuple(strategyId, awardRateInfos);
    }

    @Test
    public void testRandomDrawReturnsSingleCandidateWhenOthersExcluded() {
        List<String> excludeAwardIds = Collections.singletonList("B200");
        String awardId = algorithm.randomDraw(strategyId, excludeAwardIds);
        assertEquals("A100", awardId);
    }

    @Test
    public void testRandomDrawReturnsEmptyWhenAllAwardsExcluded() {
        List<String> excludeAwardIds = Arrays.asList("A100", "B200");
        String awardId = algorithm.randomDraw(strategyId, excludeAwardIds);
        assertEquals("", awardId);
    }

    @Test
    public void testRandomDrawNeverReturnsExcludedAward() {
        List<String> excludeAwardIds = Collections.singletonList("C300");
        for (int i = 0; i < 50; i++) {
            String awardId = algorithm.randomDraw(strategyId, excludeAwardIds);
            assertTrue("Unexpected awardId: " + awardId, awardId.equals("A100") || awardId.equals("B200"));
        }
    }
}

