package cn.itedus.lottery.domain.strategy.service.draw.impl;

import cn.itedus.lottery.domain.strategy.model.aggregates.StrategyRich;
import cn.itedus.lottery.domain.strategy.model.req.DrawReq;
import cn.itedus.lottery.domain.strategy.model.res.DrawResult;
import cn.itedus.lottery.domain.strategy.repository.IStrategyRepository;
import cn.itedus.lottery.domain.strategy.service.algorithm.IDrawAlgorithm;
import cn.itedus.lottery.domain.strategy.service.algorithm.impl.SingleRateRandomDrawAlgorithm;
import cn.itedus.lottery.domain.strategy.service.draw.DrawConfig;
import cn.itedus.lottery.infrastructure.po.Award;
import cn.itedus.lottery.infrastructure.po.Strategy;
import cn.itedus.lottery.infrastructure.po.StrategyDetail;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class DrawExecImplTest {

    private DrawExecImpl drawExec;
    private Long strategyId = 30001L;
    private Map<String, Award> awardStore;

    @Before
    public void setUp() throws Exception {
        drawExec = new DrawExecImpl();
        awardStore = new HashMap<>();

        Strategy strategy = new Strategy();
        strategy.setStrategyId(strategyId);
        strategy.setStrategyMode(1);

        StrategyDetail detail = new StrategyDetail();
        detail.setStrategyId(strategyId);
        detail.setAwardId("A100");
        detail.setAwardRate(new BigDecimal("1.00"));
        List<StrategyDetail> detailList = Collections.singletonList(detail);

        StrategyRich strategyRich = new StrategyRich(strategyId, strategy, detailList);
        Award award = new Award();
        award.setAwardId("A100");
        award.setAwardName("测试奖品A100");
        awardStore.put("A100", award);

        InMemoryStrategyRepository repository = new InMemoryStrategyRepository(strategyRich, awardStore);
        injectRepository(drawExec, repository);
        initAlgorithmMap(new SingleRateRandomDrawAlgorithm());
    }

    @Test
    public void test_doDrawExec_returnsConfiguredAward() {
        DrawReq req = new DrawReq("user-02", strategyId);
        DrawResult result = drawExec.doDrawExec(req);

        assertNotNull(result);
        assertEquals("user-02", result.getuId());
        assertEquals(strategyId, result.getStrategyId());
        assertEquals("A100", result.getRewardId());
        assertEquals("测试奖品A100", result.getAwardName());
    }

    private void injectRepository(DrawExecImpl target, IStrategyRepository repository) throws Exception {
        Field repoField = DrawExecImpl.class.getDeclaredField("strategyRepository");
        repoField.setAccessible(true);
        repoField.set(target, repository);
    }

    @SuppressWarnings("unchecked")
    private void initAlgorithmMap(IDrawAlgorithm algorithm) throws Exception {
        Field mapField = DrawConfig.class.getDeclaredField("drawAlgorithmMap");
        mapField.setAccessible(true);
        Map<Integer, IDrawAlgorithm> drawMap = (Map<Integer, IDrawAlgorithm>) mapField.get(null);
        drawMap.clear();
        drawMap.put(1, algorithm);
    }

    private static class InMemoryStrategyRepository implements IStrategyRepository {

        private final StrategyRich strategyRich;
        private final Map<String, Award> awards;

        private InMemoryStrategyRepository(StrategyRich strategyRich, Map<String, Award> awards) {
            this.strategyRich = strategyRich;
            this.awards = awards;
        }

        @Override
        public StrategyRich queryStrategyRich(Long strategyId) {
            return strategyRich;
        }

        @Override
        public Award queryAwardInfo(String awardId) {
            return awards.get(awardId);
        }
    }
}
