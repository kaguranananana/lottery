package com.anontokyo.lottery.domain.award.model.vo;

import com.alibaba.fastjson.annotation.JSONField;

/**
 * Award stock settlement message.
 */
public class AwardStockSettlementVO {

    private String uId;
    private Long orderId;
    private Long drawConfigId;
    private String awardId;

    public String getuId() {
        return uId;
    }

    public void setuId(String uId) {
        this.uId = uId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    // 已投递的 Kafka 消息仍用 strategyId 标识抽奖配置。
    @JSONField(name = "strategyId")
    public Long getDrawConfigId() {
        return drawConfigId;
    }

    @JSONField(name = "strategyId")
    public void setDrawConfigId(Long drawConfigId) {
        this.drawConfigId = drawConfigId;
    }

    public String getAwardId() {
        return awardId;
    }

    public void setAwardId(String awardId) {
        this.awardId = awardId;
    }

    @Override
    public String toString() {
        return "AwardStockSettlementVO{" +
                "uId='" + uId + '\'' +
                ", orderId=" + orderId +
                ", drawConfigId=" + drawConfigId +
                ", awardId='" + awardId + '\'' +
                '}';
    }
}
