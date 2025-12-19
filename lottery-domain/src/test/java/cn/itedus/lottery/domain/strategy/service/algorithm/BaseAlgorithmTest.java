package cn.itedus.lottery.domain.strategy.service.algorithm;

import cn.itedus.lottery.domain.strategy.model.vo.AwardRateInfo;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * BaseAlgorithm 单元测试（JUnit 4）
 */
public class BaseAlgorithmTest {

    /**
     * 为了断言 BaseAlgorithm 内部的 protected 字段，做一个可测试子类暴露只读访问。
     */
    static class TestableAlgorithm extends BaseAlgorithm {
        public String[] getRateTuple(Long strategyId) {
            return rateTupleMap.get(strategyId);
        }

        public List<AwardRateInfo> getAwardRateInfoList(Long strategyId) {
            return awardRateInfoMap.get(strategyId);
        }

        @Override
        public String randomDraw(Long strategyId, List<String> excludeAwardIds) {
            return "";
        }
    }

    @Test
    public void test_isExistRateTuple_beforeAndAfterInit() {
        TestableAlgorithm algorithm = new TestableAlgorithm();
        Long strategyId = 10001L;

        // 初始化前不存在
        Assert.assertFalse(algorithm.isExistRateTuple(strategyId));

        // 初始化
        List<AwardRateInfo> rateInfoList = Arrays.asList(
                new AwardRateInfo("A1", new BigDecimal("0.30")),
                new AwardRateInfo("A2", new BigDecimal("0.20")),
                new AwardRateInfo("A3", new BigDecimal("0.50"))
        );
        algorithm.initRateTuple(strategyId, rateInfoList);

        // 初始化后存在
        Assert.assertTrue(algorithm.isExistRateTuple(strategyId));

    }

    @Test
    public void test_initRateTuple_shouldStoreAwardRateInfo_andFillTuple() {
        TestableAlgorithm algorithm = new TestableAlgorithm();
        Long strategyId = 10002L;

        List<AwardRateInfo> rateInfoList = Arrays.asList(
                new AwardRateInfo("A1", new BigDecimal("0.30")),
                new AwardRateInfo("A2", new BigDecimal("0.20")),
                new AwardRateInfo("A3", new BigDecimal("0.50"))
        );

        algorithm.initRateTuple(strategyId, rateInfoList);

        // 断言：awardRateInfoMap 是否存入
        Assert.assertNotNull(algorithm.getAwardRateInfoList(strategyId));
        Assert.assertEquals(3, algorithm.getAwardRateInfoList(strategyId).size());

        // 断言：rateTuple 是否创建
        String[] tuple = algorithm.getRateTuple(strategyId);
        Assert.assertNotNull(tuple);
        Assert.assertEquals(128, tuple.length);

        // 统计输出（摘要信息，便于肉眼观察）
        Stats stats = analyzeTuple(tuple, Arrays.asList("A1", "A2", "A3"));
        System.out.println("[BaseAlgorithm] strategyId=" + strategyId
                + ", tupleLength=" + tuple.length
                + ", filledSlots=" + stats.filledSlots
                + ", distinctAwards=" + stats.distinctAwards
                + ", counts=" + stats.counts);

        // 断言：至少能看到每个 awardId 出现过
        Assert.assertTrue("tuple 中未发现 A1", stats.counts.get("A1") > 0);
        Assert.assertTrue("tuple 中未发现 A2", stats.counts.get("A2") > 0);
        Assert.assertTrue("tuple 中未发现 A3", stats.counts.get("A3") > 0);

        // 断言：filled 合理（<= 100 是因为总映射次数=100，哈希冲突会减少非空槽位数）
        Assert.assertTrue("tuple 槽位不应全为空", stats.filledSlots > 0);
        Assert.assertTrue("tuple 非空槽位数不应超过 100（总映射次数）", stats.filledSlots <= 100);
    }

    private static class Stats {
        final int filledSlots;
        final int distinctAwards;
        final java.util.Map<String, Integer> counts;

        private Stats(int filledSlots, int distinctAwards, java.util.Map<String, Integer> counts) {
            this.filledSlots = filledSlots;
            this.distinctAwards = distinctAwards;
            this.counts = counts;
        }
    }

    private static Stats analyzeTuple(String[] tuple, List<String> awardIds) {
        int filled = 0;
        java.util.Set<String> distinct = new java.util.HashSet<>();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (String id : awardIds) counts.put(id, 0);

        for (String v : tuple) {
            if (v != null) {
                filled++;
                distinct.add(v);
                if (counts.containsKey(v)) {
                    counts.put(v, counts.get(v) + 1);
                }
            }
        }
        return new Stats(filled, distinct.size(), counts);
    }

    @Test
    public void test_initRateTuple_sameStrategyId_shouldReuseTupleArrayInstance() {
        TestableAlgorithm algorithm = new TestableAlgorithm();
        Long strategyId = 10003L;

        List<AwardRateInfo> first = Arrays.asList(
                new AwardRateInfo("A1", new BigDecimal("0.50")),
                new AwardRateInfo("A2", new BigDecimal("0.50"))
        );
        algorithm.initRateTuple(strategyId, first);
        String[] tuple1 = algorithm.getRateTuple(strategyId);
        Assert.assertNotNull(tuple1);

        // 再次 init 同一 strategyId（computeIfAbsent 应复用旧数组实例）
        List<AwardRateInfo> second = Arrays.asList(
                new AwardRateInfo("B1", new BigDecimal("1.00"))
        );
        algorithm.initRateTuple(strategyId, second);
        String[] tuple2 = algorithm.getRateTuple(strategyId);

        Assert.assertSame("同一 strategyId 的 tuple 数组实例应被复用（computeIfAbsent）", tuple1, tuple2);

        // awardRateInfoMap 应更新为新的配置
        Assert.assertEquals(1, algorithm.getAwardRateInfoList(strategyId).size());
        Assert.assertEquals("B1", algorithm.getAwardRateInfoList(strategyId).get(0).getAwardId());
    }

    private static boolean containsAwardId(String[] tuple, String awardId) {
        for (String v : tuple) {
            if (awardId.equals(v)) return true;
        }
        return false;
    }

    private static int countNonNull(String[] tuple) {
        int c = 0;
        for (String v : tuple) {
            if (v != null) c++;
        }
        return c;
    }
}
