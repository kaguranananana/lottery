package com.anontokyo.lottery.domain.strategy.repository;

import com.anontokyo.lottery.domain.strategy.model.aggregates.StrategyRich;
import com.anontokyo.lottery.domain.strategy.model.vo.AwardBriefVO;

import java.util.List;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */

public interface IStrategyRepository {
    StrategyRich queryStrategyRich(Long strategyId);

    AwardBriefVO queryAwardInfo(String awardId);

    List<String> queryNoStockStrategyAwardList(Long strategyId);

    /**
     * 扣减库存
     * @param strategyId
     * @param awardId
     * @return
     */
    boolean deductStock(Long strategyId, String awardId);

    /**
     * 扣减奖品库存（通过Redis）
     * @param strategyId 策略ID
     * @param awardId    奖品ID
     * @param awardCount 奖品总库存
     * @return true=扣减成功
     */
    boolean deductStockByRedis(Long strategyId, String awardId, Integer awardCount);

    /**
     * 恢复奖品库存缓存（删除 token key）
     * @param stockTokenKey token key
     */
    void recoverStrategyAwardStockByRedis(String stockTokenKey);
}
