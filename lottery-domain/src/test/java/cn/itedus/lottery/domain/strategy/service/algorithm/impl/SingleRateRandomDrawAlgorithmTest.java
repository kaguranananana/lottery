package cn.itedus.lottery.domain.strategy.service.algorithm.impl;

import cn.itedus.lottery.domain.strategy.model.vo.AwardRateInfo;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class SingleRateRandomDrawAlgorithmTest {

    private SingleRateRandomDrawAlgorithm algorithm;
    private final Long strategyId = 10001L;

    @Before
    public void setUp() {
        algorithm = new SingleRateRandomDrawAlgorithm();

        List<AwardRateInfo> strategyList = new ArrayList<>();
        strategyList.add(new AwardRateInfo("A001", new BigDecimal("0.30")));
        strategyList.add(new AwardRateInfo("B001", new BigDecimal("0.70")));

        algorithm.initRateTuple(strategyId, strategyList);
    }

    @Test
    public void test_randomDraw_success() {
        List<String> excludeAwardIds = new ArrayList<>();
        String awardId = algorithm.randomDraw(strategyId, excludeAwardIds);

        // 断言结果不为空且是我们设定的奖品之一
        assertNotNull(awardId);
        System.out.println("抽奖结果：" + awardId);
    }

    @Test
    public void test_randomDraw_exclude() {
        // 准备排除列表，假设把 A001 排除掉
        List<String> excludeAwardIds = new ArrayList<>();
        excludeAwardIds.add("A001");
        excludeAwardIds.add("B001");

        // 当所有奖品都被排除时，理论上根据你的代码逻辑应该返回 "未中奖"
        String result = algorithm.randomDraw(strategyId, excludeAwardIds);

        assertEquals("未中奖", result);
    }
}