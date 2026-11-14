package com.anontokyo.lottery.domain.award.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;

import java.util.List;

/**
 * @description: 奖品表仓储服务接口
 * @author：anontokyo
 * @date: 2026/1/10
 */
public interface IOrderRepository {

    /**
     * CAS 标记发奖完成（award_fulfillment_status -> SUCCESS，且仅当未完成时生效）。
     * @return true 表示本次调用完成了首次标记；false 表示订单此前已完成（幂等短路）
     */
    boolean markUserAwardGranted(String uId, Long orderId, String awardId);

    /**
     * 条件推进发奖 MQ 状态：消费确认实际发奖后写 COMPLETE。
     */
    void updateFulfillmentEventStatus(String uId, Long orderId, Integer fulfillmentEventStatus);

    /**
     * 条件推进库存 MQ 状态：消费后写 COMPLETE，并登记发奖义务。
     * @return true 表示本次推进状态，库存完成时同时领取首发发奖义务
     */
    boolean updateStockSettlementEventStatus(String uId, Long orderId, Integer stockSettlementEventStatus);

    /** 库存条件扣减失败：INIT/FAIL/SENT 转 BLOCKED 等待对账；持久化失败抛出，COMPLETE 不回退。 */
    void markStockSettlementFailed(String uId, Long orderId);

    enum OutboxKind { STOCK, FULFILLMENT }

    /** 读取到期候选，投递前仍需在数据库领取本次发送。 */
    List<DrawOrderVO> scanOutbox(OutboxKind kind, int limit);

    /** 到期条件领取只移动下次核对时间，终态由消费者写入。 */
    boolean claimOutbox(OutboxKind kind, long orderId, int leaseSeconds);

    DrawOrderVO findOrder(String uId, Long orderId);

    /**
     * 查询订单是否已经完成发奖。
     */
    boolean isAwardCompleted(String uId, Long orderId);

    /**
     * Query invoice payload by order id.
     */
    AwardFulfillmentEvent queryFulfillmentEventByOrderId(String uId, Long orderId);

    /**
     * 按 take 关联查询中奖订单，用于 requestId 幂等回放；无订单返回 null。
     */
    DrawOrderVO queryOrderByTakeId(String uId, Long takeId);

    /**
     * 保存中奖订单并锁定对应参与记录（本地事务由 infrastructure 承担）。
     * @return SUCCESS / NO_UPDATE(锁定失败) / INDEX_DUP(订单重复)
     */
    Constants.ResponseCode saveDrawOrderWithTakeLock(DrawOrderVO drawOrder, long deadline);

    enum StockTransactionState { COMMITTED, ROLLED_BACK, UNKNOWN }

    /** 订单存在即证明已提交。过期的未决消息需在参与锁内判定。 */
    StockTransactionState checkStockTransaction(String uId, long orderId, long takeId, long deadline);

    /** 对账执行进程退出或回补失败遗留的 Redis 预扣。 */
    void recoverStockReservations(int limit);

    /**
     * Whether stock settlement is already complete.
     */
    boolean isStockSettlementComplete(String uId, Long orderId);

}
