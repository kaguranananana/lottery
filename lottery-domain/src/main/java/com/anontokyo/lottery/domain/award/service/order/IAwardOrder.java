package com.anontokyo.lottery.domain.award.service.order;

import com.anontokyo.lottery.domain.award.model.req.AwardOrderReq;
import com.anontokyo.lottery.domain.award.model.res.AwardOrderResult;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;

import java.util.List;

/**
 * 奖品订单领域服务。
 */
public interface IAwardOrder {

    /**
     * 完成中奖单落库，并负责奖品库存结算与失败补偿。
     */
    AwardOrderResult placeOrder(AwardOrderReq awardOrderReq);

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

    /**
     * 扫描待补偿的发奖MQ订单。
     */
    List<AwardFulfillmentEvent> scanFulfillmentEventStatus(int limit);

    /**
     * Scan award stock settlement outbox.
     */
    List<AwardStockSettlementVO> scanStockSettlementEventStatus(int limit);

    /**
     * Query invoice payload by order id.
     */
    AwardFulfillmentEvent queryFulfillmentEventByOrderId(String uId, Long orderId);

    /**
     * Whether stock settlement is already complete.
     */
    boolean isStockSettlementComplete(String uId, Long orderId);
}
