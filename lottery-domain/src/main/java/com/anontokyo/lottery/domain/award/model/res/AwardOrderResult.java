package com.anontokyo.lottery.domain.award.model.res;

import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;

/**
 * 奖品订单落单结果。
 */
public class AwardOrderResult {

    private String code;
    private String info;
    private AwardFulfillmentEvent fulfillmentEvent;

    public AwardOrderResult() {
    }

    public AwardOrderResult(String code, String info) {
        this.code = code;
        this.info = info;
    }

    public AwardOrderResult(String code, String info, AwardFulfillmentEvent fulfillmentEvent) {
        this.code = code;
        this.info = info;
        this.fulfillmentEvent = fulfillmentEvent;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getInfo() {
        return info;
    }

    public void setInfo(String info) {
        this.info = info;
    }

    public AwardFulfillmentEvent getAwardFulfillmentEvent() {
        return fulfillmentEvent;
    }

    public void setAwardFulfillmentEvent(AwardFulfillmentEvent fulfillmentEvent) {
        this.fulfillmentEvent = fulfillmentEvent;
    }
}
