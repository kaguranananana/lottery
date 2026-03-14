package com.anontokyo.lottery.domain.award.model.vo;

/**
 * Award stock settlement message.
 */
public class AwardStockSettlementVO {

    private String uId;
    private Long orderId;
    private Long strategyId;
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

    public Long getStrategyId() {
        return strategyId;
    }

    public void setStrategyId(Long strategyId) {
        this.strategyId = strategyId;
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
                ", strategyId=" + strategyId +
                ", awardId='" + awardId + '\'' +
                '}';
    }
}
