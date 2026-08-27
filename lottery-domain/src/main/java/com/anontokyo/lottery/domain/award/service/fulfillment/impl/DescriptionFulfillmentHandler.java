package com.anontokyo.lottery.domain.award.service.fulfillment.impl;

import com.anontokyo.lottery.common.Constants;
import org.springframework.stereotype.Component;

/**
 * 文字描述类奖品。
 */
@Component
public class DescriptionFulfillmentHandler extends AbstractAwardFulfillmentHandler {

    @Override
    public int supportType() {
        return Constants.AwardType.DESC.getCode();
    }
}
