package com.anontokyo.lottery.domain.award.service.fulfillment.impl;

import com.anontokyo.lottery.common.Constants;
import org.springframework.stereotype.Component;

/**
 * 优惠券类奖品。
 */
@Component
public class CouponFulfillmentHandler extends AbstractAwardFulfillmentHandler {

    @Override
    public int supportType() {
        return Constants.AwardType.CouponGoods.getCode();
    }
}
