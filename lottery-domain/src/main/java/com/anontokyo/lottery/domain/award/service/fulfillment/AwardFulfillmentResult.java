package com.anontokyo.lottery.domain.award.service.fulfillment;

import com.anontokyo.lottery.common.Constants;

/**
 * 发奖结果。code 语义沿用 Constants.AwardState（0 等待 / 1 成功 / 2 失败）。
 */
public class AwardFulfillmentResult {

    private final String uId;
    private final Constants.AwardState state;
    private final String info;
    /** true 表示本次调用完成了首次发奖标记；false 表示该订单此前已处理过 */
    private final boolean firstCompletion;

    public AwardFulfillmentResult(String uId, Constants.AwardState state, String info, boolean firstCompletion) {
        this.uId = uId;
        this.state = state;
        this.info = info;
        this.firstCompletion = firstCompletion;
    }

    public String getuId() {
        return uId;
    }

    public Constants.AwardState getState() {
        return state;
    }

    public String getInfo() {
        return info;
    }

    public boolean isFirstCompletion() {
        return firstCompletion;
    }

    public Integer getCode() {
        return state.getCode();
    }
}
