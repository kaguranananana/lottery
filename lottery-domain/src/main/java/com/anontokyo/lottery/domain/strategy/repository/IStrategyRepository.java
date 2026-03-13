package com.anontokyo.lottery.domain.strategy.repository;

import com.anontokyo.lottery.domain.strategy.model.aggregates.StrategyRich;
import com.anontokyo.lottery.domain.strategy.model.vo.AwardBriefVO;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyAwardStockDeductResultVO;

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
     * 扣减奖品库存（通过Redis）。
     *
     * @param strategyId 策略ID
     * @param awardId 奖品ID
     * @return 扣减结果（成功/售罄）
     */
    StrategyAwardStockDeductResultVO deductStockByRedis(Long strategyId, String awardId);

    /**
     * 回补 Redis 奖品剩余库存。
     */
    void recoverStrategyAwardStockByRedis(Long strategyId, String awardId);

    /**
     * 回补数据库奖品剩余库存。
     */
    void recoverStock(Long strategyId, String awardId);
}
