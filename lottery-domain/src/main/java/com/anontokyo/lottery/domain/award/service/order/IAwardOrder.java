package com.anontokyo.lottery.domain.award.service.order;

import com.anontokyo.lottery.domain.award.model.req.AwardOrderReq;
import com.anontokyo.lottery.domain.award.model.res.AwardOrderResult;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;

public interface IAwardOrder {
    /** 原子消费参与记录并创建中奖订单。 */
    AwardOrderResult placeOrder(AwardOrderReq awardOrderReq);

    AwardFulfillmentEvent queryFulfillmentEventByOrderId(String uId, Long orderId);
}
