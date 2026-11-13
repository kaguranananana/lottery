package com.anontokyo.lottery.domain.award.model.vo;


/**
 * 库存消息携带活动号供观测；消费者按用户和订单号重读订单，兼容没有 activityId 的旧消息。
 */
public class AwardStockSettlementVO {

    private String uId;
    private Long orderId;
    private Long activityId;
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

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
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
                ", activityId=" + activityId +
                ", awardId='" + awardId + '\'' +
                '}';
    }
}
