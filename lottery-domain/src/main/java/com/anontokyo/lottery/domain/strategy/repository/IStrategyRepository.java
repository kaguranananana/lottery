package com.anontokyo.lottery.domain.strategy.repository;

import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.strategy.model.snapshot.StrategySnapshot;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyAwardStockDeductResultVO;

/**
 * 抽奖策略仓储。策略快照是抽奖主链路唯一的配置读取入口，
 * 两级缓存由 infrastructure 实现，domain 不感知缓存细节。
 */
public interface IStrategyRepository {

    /**
     * 读取含整数权重的策略快照（Caffeine+Redis 两级缓存，短 TTL 最终一致）。
     */
    StrategySnapshot queryStrategySnapshot(Long strategyId);

    /**
     * 结算奖品库存：同一数据库事务内「写结算账本 + 条件扣减」；
     * 账本 order_id 唯一，重复投递在此被拦截，返回 true 表示已结算（或早已结算）。
     * 返回 false 表示扣减时库存不足（需要人工对账）。
     */
    boolean settleAwardStock(AwardStockSettlementVO settlement);

    /**
     * Redis 条件原子扣减奖品库存。
     */
    StrategyAwardStockDeductResultVO deductStockByRedis(Long strategyId, String awardId);

    /**
     * 回补 Redis 奖品剩余库存（下单失败回滚用）。
     */
    void recoverStrategyAwardStockByRedis(Long strategyId, String awardId);
}
