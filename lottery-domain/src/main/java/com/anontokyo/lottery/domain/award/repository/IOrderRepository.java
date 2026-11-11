package com.anontokyo.lottery.domain.award.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
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
     * 条件推进发奖 MQ 状态：发送回调写 SENT/FAIL，消费确认发奖后写 COMPLETE。
     */
    void updateFulfillmentEventStatus(String uId, Long orderId, Integer fulfillmentEventStatus);

    /**
     * 条件推进库存 MQ 状态：发送失败不覆盖 SENT，所有发送回调不覆盖 COMPLETE。
     */
    void updateStockSettlementEventStatus(String uId, Long orderId, Integer stockSettlementEventStatus);

    /** 库存条件扣减失败：INIT/FAIL/SENT 转 BLOCKED 等待对账；持久化失败抛出，COMPLETE 不回退。 */
    void markStockSettlementFailed(String uId, Long orderId);

    enum OutboxKind { STOCK, FULFILLMENT }

    /** 读取到期候选，投递前仍需在数据库领取本次发送。 */
    List<DrawOrderVO> scanOutbox(OutboxKind kind, int limit);

    /** CAS 推进投递代数，并设置可恢复的发送租期。 */
    boolean claimOutbox(OutboxKind kind, long orderId, int attempt, boolean immediate, int leaseSeconds);

    /** 只允许当前代数的回调更新下次重试时间。 */
    void finishOutbox(OutboxKind kind, long orderId, int attempt, boolean sent, int delaySeconds);

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
    Constants.ResponseCode saveDrawOrderWithTakeLock(DrawOrderVO drawOrder);

    /**
     * Whether stock settlement is already complete.
     */
    boolean isStockSettlementComplete(String uId, Long orderId);

}
