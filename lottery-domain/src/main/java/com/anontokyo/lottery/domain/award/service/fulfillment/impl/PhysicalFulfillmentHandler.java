package com.anontokyo.lottery.domain.award.service.fulfillment.impl;

import com.anontokyo.lottery.common.Constants;
import org.springframework.stereotype.Component;

/**
 * 实物类奖品（真实接入时需在此补充物流下单与收货地址校验）。
 */
@Component
public class PhysicalFulfillmentHandler extends AbstractAwardFulfillmentHandler {

    @Override
    public int supportType() {
        return Constants.AwardType.PhysicalGoods.getCode();
    }
}
