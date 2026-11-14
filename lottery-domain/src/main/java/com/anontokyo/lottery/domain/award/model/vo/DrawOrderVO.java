package com.anontokyo.lottery.domain.award.model.vo;

import java.util.Date;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/23
 */
public class DrawOrderVO {

    /**
     * 用户ID
     */
    private String uId;

    /**
     * 活动领取ID
     */
    private Long takeId;
    /**
     * 活动ID
     */
    private Long activityId;
    /**
     * 订单ID
     */
    private Long orderId;

    /** 实际完成时间由发奖 CAS 写入。 */
    private Date fulfilledAt;

    public Date getFulfilledAt() {
        return fulfilledAt;
    }

    public void setFulfilledAt(Date fulfilledAt) {
        this.fulfilledAt = fulfilledAt;
    }
    /**
     * 发奖状态
     */
    private Integer awardFulfillmentStatus;
    /**
     * 发奖ID
     */
    private String awardId;
    /**
     * 奖品类型（1:文字描述、2:兑换码、3:优惠券、4:实物奖品）
     */
    private Integer awardType;
    /**
     * 奖品名称
     */
    private String awardName;
    /**
     * 奖品内容「文字描述、Key、码」
     */
    private String awardContent;
    /**
     * 发奖事件状态（0待投递、1消费完成、2投递失败、3已投递）
     */
    private Integer fulfillmentEventStatus;
    /**
     * 库存结算事件状态（0待投递、1结算完成、2投递失败、3已投递、4待对账）。
     */
    private Integer stockSettlementEventStatus;

    public String getuId() {
        return uId;
    }

    public void setuId(String uId) {
        this.uId = uId;
    }

    public Long getTakeId() {
        return takeId;
    }

    public void setTakeId(Long takeId) {
        this.takeId = takeId;
    }

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Integer getAwardFulfillmentStatus() {
        return awardFulfillmentStatus;
    }

    public void setAwardFulfillmentStatus(Integer awardFulfillmentStatus) {
        this.awardFulfillmentStatus = awardFulfillmentStatus;
    }

    public String getAwardId() {
        return awardId;
    }

    public void setAwardId(String awardId) {
        this.awardId = awardId;
    }

    public Integer getAwardType() {
        return awardType;
    }

    public void setAwardType(Integer awardType) {
        this.awardType = awardType;
    }

    public String getAwardName() {
        return awardName;
    }

    public void setAwardName(String awardName) {
        this.awardName = awardName;
    }

    public String getAwardContent() {
        return awardContent;
    }

    public void setAwardContent(String awardContent) {
        this.awardContent = awardContent;
    }

    public Integer getFulfillmentEventStatus() {
        return fulfillmentEventStatus;
    }

    public void setFulfillmentEventStatus(Integer fulfillmentEventStatus) {
        this.fulfillmentEventStatus = fulfillmentEventStatus;
    }

    public Integer getStockSettlementEventStatus() {
        return stockSettlementEventStatus;
    }

    public void setStockSettlementEventStatus(Integer stockSettlementEventStatus) {
        this.stockSettlementEventStatus = stockSettlementEventStatus;
    }

    @Override
    public String toString() {
        return "DrawOrderVO{" +
                "uId='" + uId + '\'' +
                ", takeId=" + takeId +
                ", activityId=" + activityId +
                ", orderId=" + orderId +
                ", awardFulfillmentStatus=" + awardFulfillmentStatus +
                ", awardId='" + awardId + '\'' +
                ", awardType=" + awardType +
                ", awardName='" + awardName + '\'' +
                ", awardContent='" + awardContent + '\'' +
                ", fulfillmentEventStatus=" + fulfillmentEventStatus +
                ", stockSettlementEventStatus=" + stockSettlementEventStatus +
                '}';
    }


}
