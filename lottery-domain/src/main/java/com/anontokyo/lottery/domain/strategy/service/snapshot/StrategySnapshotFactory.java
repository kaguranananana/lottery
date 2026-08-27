package com.anontokyo.lottery.domain.strategy.service.snapshot;

import com.anontokyo.lottery.domain.strategy.model.aggregates.StrategyRich;
import com.anontokyo.lottery.domain.strategy.model.snapshot.StrategySnapshot;
import com.anontokyo.lottery.domain.strategy.model.vo.AwardBriefVO;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyDetailBriefVO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把策略配置（BigDecimal 概率）编译成整数权重快照。
 * 精度 1e-6：支持 0.000001（0.0001%）到 100% 的任意概率，不再有 rate*100 的整型截断。
 * 概率总和不足 100% 时差额自动成为 NO_PRIZE；超过 100% 属于配置错误，直接抛出。
 * 快照一经构建视为不可变对象，抽奖线程只读访问。
 */
public class StrategySnapshotFactory {

    /** 概率精度：1 权重 = 0.000001 */
    public static final long WEIGHT_SCALE = 1_000_000L;

    public StrategySnapshot build(StrategyRich rich, AwardInfoResolver awardInfoResolver) {
        List<StrategyDetailBriefVO> details = rich.getStrategyDetailList();
        if (details == null || details.isEmpty()) {
            throw new IllegalStateException("strategy has no award detail, strategyId:" + rich.getStrategyId());
        }

        List<String> ids = new ArrayList<>(details.size());
        List<Long> weights = new ArrayList<>(details.size());
        Map<String, AwardBriefVO> awardInfos = new LinkedHashMap<>(details.size() * 2);
        long sumWeight = 0L;

        for (StrategyDetailBriefVO detail : details) {
            BigDecimal rate = detail.getAwardRate();
            if (rate == null || rate.signum() < 0) {
                throw new IllegalArgumentException("award rate must be >= 0, strategyId:" + rich.getStrategyId()
                        + " awardId:" + detail.getAwardId() + " rate:" + rate);
            }
            long weight = rate.multiply(BigDecimal.valueOf(WEIGHT_SCALE))
                    .setScale(0, BigDecimal.ROUND_DOWN).longValueExact();
            if (weight <= 0L) {
                continue;
            }

            AwardBriefVO briefVO = awardInfoResolver.resolve(detail.getAwardId());
            if (briefVO == null) {
                throw new IllegalStateException("award config missing, strategyId:" + rich.getStrategyId()
                        + " awardId:" + detail.getAwardId());
            }

            sumWeight += weight;
            if (sumWeight > WEIGHT_SCALE) {
                throw new IllegalArgumentException(
                        "total award probability exceeds 100%, strategyId:" + rich.getStrategyId());
            }
            ids.add(detail.getAwardId());
            weights.add(weight);
            awardInfos.put(detail.getAwardId(), briefVO);
        }

        return compile(rich, ids, weights, sumWeight, awardInfos);
    }

    private StrategySnapshot compile(StrategyRich rich, List<String> ids, List<Long> weights,
            long sumWeight, Map<String, AwardBriefVO> awardInfos) {
        long noPrizeWeight = WEIGHT_SCALE - sumWeight;
        int slots = ids.size() + (noPrizeWeight > 0 ? 1 : 0);

        String[] awardIds = new String[slots];
        long[] prefix = new long[slots];

        long cumulative = 0L;
        for (int i = 0; i < ids.size(); i++) {
            cumulative += weights.get(i);
            awardIds[i] = ids.get(i);
            prefix[i] = cumulative;
        }
        if (cursor(slots, ids.size())) {
            // 剩余概率整体划入 NO_PRIZE，不改任何真实奖品的概率
            awardIds[ids.size()] = null;
            prefix[ids.size()] = WEIGHT_SCALE;
        }

        StrategySnapshot snapshot = new StrategySnapshot();
        snapshot.setStrategyId(rich.getStrategyId());
        snapshot.setStrategyMode(rich.getStrategy().getStrategyMode());
        snapshot.setGrantType(rich.getStrategy().getGrantType());
        snapshot.setGrantDate(rich.getStrategy().getGrantDate());
        snapshot.setAwardIds(awardIds);
        snapshot.setCumulativeWeights(prefix);
        snapshot.setAwardInfos(awardInfos);
        return snapshot;
    }

    private boolean cursor(int slots, int size) {
        return slots > size;
    }

    /** 奖品详情解析器，由 repository 层实现以复用既有缓存查询 */
    public interface AwardInfoResolver {
        AwardBriefVO resolve(String awardId);
    }
}
