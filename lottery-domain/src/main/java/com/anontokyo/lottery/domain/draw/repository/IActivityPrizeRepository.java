package com.anontokyo.lottery.domain.draw.repository;

import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.draw.model.snapshot.ActivityPrizeSnapshot;
import com.anontokyo.lottery.domain.draw.model.vo.ActivityPrizeStockDeductResultVO;

/**
 * 活动奖池仓储。活动奖池快照是抽奖主链路唯一的配置读取入口，
 * 两级缓存由 infrastructure 实现，domain 不感知缓存细节。
 */
public interface IActivityPrizeRepository {

    /**
     * 读取含整数权重的活动奖池快照（Caffeine+Redis 两级缓存，短 TTL 最终一致）。
     */
    ActivityPrizeSnapshot queryActivityPrizeSnapshot(Long activityId);

    /**
     * 结算奖品库存：同一数据库事务内「写结算账本 + 条件扣减」；
     * 账本 order_id 唯一，重复投递在此被拦截，返回 true 表示已结算（或早已结算）。
     * 返回 false 表示扣减时库存不足（需要人工对账）。
     */
    boolean settleAwardStock(AwardStockSettlementVO settlement);

    /**
     * Redis 条件原子扣减奖品库存。
     */
    ActivityPrizeStockDeductResultVO deductStockByRedis(Long activityId, String awardId);

    /**
     * 回补 Redis 奖品剩余库存（下单失败回滚用）。
     */
    void recoverActivityPrizeStockByRedis(Long activityId, String awardId);
}
