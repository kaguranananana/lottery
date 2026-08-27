package com.anontokyo.lottery.domain.award.service.fulfillment.impl;

import com.anontokyo.lottery.common.Constants;
import org.springframework.stereotype.Component;

/**
 * 兑换码类奖品。
 */
@Component
public class RedeemCodeFulfillmentHandler extends AbstractAwardFulfillmentHandler {

    @Override
    public int supportType() {
        return Constants.AwardType.RedeemCodeGoods.getCode();
    }
}
