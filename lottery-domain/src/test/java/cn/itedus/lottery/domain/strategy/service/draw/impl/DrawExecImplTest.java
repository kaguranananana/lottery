package cn.itedus.lottery.domain.strategy.service.draw.impl;

import cn.itedus.lottery.common.Constants;
import cn.itedus.lottery.domain.strategy.model.aggregates.StrategyRich;
import cn.itedus.lottery.domain.strategy.model.req.DrawReq;
import cn.itedus.lottery.domain.strategy.model.res.DrawResult;
import cn.itedus.lottery.domain.strategy.model.vo.AwardRateInfo;
import cn.itedus.lottery.domain.strategy.repository.IStrategyRepository;
import cn.itedus.lottery.domain.strategy.service.algorithm.IDrawAlgorithm;
import cn.itedus.lottery.infrastructure.po.Award;
import cn.itedus.lottery.infrastructure.po.Strategy;
import cn.itedus.lottery.infrastructure.po.StrategyDetail;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class DrawExecImplTest {

    private TestableDrawExec drawExec;
    private FakeStrategyRepository strategyRepository;
    private IDrawAlgorithm drawAlgorithm;
    private final Long strategyId = 30001L;

    @Before
    public void setUp() {
        drawAlgorithm = new DeterministicDrawAlgorithm();

        TestableDrawExec.resetAlgorithmGroup(drawAlgorithm);

        strategyRepository = new FakeStrategyRepository();
        strategyRepository.setStrategyRich(buildStrategyRich());
        strategyRepository.setAwardInfo(buildAwardMap());

        drawExec = new TestableDrawExec();
        drawExec.setStrategyRepository(strategyRepository);
    }

    @Test
    public void test_doDrawExec_success() {
        DrawReq req = new DrawReq("u123", strategyId);
        DrawResult result = drawExec.doDrawExec(req);

        assertEquals(Constants.DrawState.SUCCESS.getCode(), result.getDrawState());
        assertNotNull(result.getDrawAwardInfo());
        assertEquals("A001", result.getDrawAwardInfo().getAwardId());
    }

    @Test
    public void test_doDrawExec_deductStockFail() {
        strategyRepository.setDeductStockSuccess(false);

        DrawReq req = new DrawReq("u123", strategyId);
        DrawResult result = drawExec.doDrawExec(req);

        assertEquals(Constants.DrawState.FAIL.getCode(), result.getDrawState());
        assertNull(result.getDrawAwardInfo());
    }

    private StrategyRich buildStrategyRich() {
        Strategy strategy = new Strategy();
        strategy.setStrategyId(strategyId);
        strategy.setStrategyMode(Constants.StrategyMode.SINGLE.getCode());

        List<StrategyDetail> detailList = new ArrayList<>();

        StrategyDetail d1 = new StrategyDetail();
        d1.setStrategyId(strategyId);
        d1.setAwardId("A001");
        d1.setAwardRate(new BigDecimal("0.50"));
        detailList.add(d1);

        StrategyDetail d2 = new StrategyDetail();
        d2.setStrategyId(strategyId);
        d2.setAwardId("B001");
        d2.setAwardRate(new BigDecimal("0.50"));
        detailList.add(d2);

        return new StrategyRich(strategyId, strategy, detailList);
    }

    private Map<String, Award> buildAwardMap() {
        Map<String, Award> map = new HashMap<>();

        Award a1 = new Award();
        a1.setAwardId("A001");
        a1.setAwardName("测试奖品A");
        map.put("A001", a1);

        Award a2 = new Award();
        a2.setAwardId("B001");
        a2.setAwardName("测试奖品B");
        map.put("B001", a2);

        return map;
    }

    private static class TestableDrawExec extends DrawExecImpl {
        static void resetAlgorithmGroup(IDrawAlgorithm algorithm) {
            drawAlgorithmGroup.clear();
            drawAlgorithmGroup.put(Constants.StrategyMode.SINGLE.getCode(), algorithm);
        }

        void setStrategyRepository(IStrategyRepository repository) {
            this.strategyRepository = repository;
        }
    }

    private static class FakeStrategyRepository implements IStrategyRepository {
        private StrategyRich strategyRich;
        private Map<String, Award> awardInfo = new HashMap<>();
        private boolean deductStockSuccess = true;
        private List<String> noStockAwardIds = Collections.emptyList();

        void setStrategyRich(StrategyRich strategyRich) {
            this.strategyRich = strategyRich;
        }

        void setAwardInfo(Map<String, Award> awardInfo) {
            this.awardInfo = awardInfo;
        }

        void setDeductStockSuccess(boolean deductStockSuccess) {
            this.deductStockSuccess = deductStockSuccess;
        }

        @Override
        public StrategyRich queryStrategyRich(Long strategyId) {
            return strategyRich;
        }

        @Override
        public Award queryAwardInfo(String awardId) {
            return awardInfo.get(awardId);
        }

        @Override
        public List<String> queryNoStockStrategyAwardList(Long strategyId) {
            return noStockAwardIds;
        }

        @Override
        public boolean deductStock(Long strategyId, String awardId) {
            return deductStockSuccess;
        }
    }

    private static class DeterministicDrawAlgorithm implements IDrawAlgorithm {
        private final Map<Long, List<AwardRateInfo>> awardRateInfoMap = new HashMap<>();

        @Override
        public void initRateTuple(Long strategyId, List<AwardRateInfo> awardRateInfoList) {
            awardRateInfoMap.put(strategyId, awardRateInfoList);
        }

        @Override
        public boolean isExistRateTuple(Long strategyId) {
            return awardRateInfoMap.containsKey(strategyId);
        }

        @Override
        public String randomDraw(Long strategyId, List<String> excludeAwardIds) {
            List<AwardRateInfo> list = awardRateInfoMap.get(strategyId);
            if (list == null || list.isEmpty()) {
                return null;
            }
            for (AwardRateInfo info : list) {
                if (!excludeAwardIds.contains(info.getAwardId())) {
                    return info.getAwardId();
                }
            }
            return null;
        }
    }
}
