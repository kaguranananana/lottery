package com.anontokyo.lottery.domain.draw.model.vo;

/**
 * @description: DrawConfig award stock MQ message object
 * @author: anontokyo
 * @date: 2026/3/9
 */
public class DrawConfigPrizeStockVO {

    /** DrawConfig ID */
    private Long drawConfigId;

    /** Award ID */
    private String awardId;

    /** Unique business id for message deduplication */
    private Long orderId;

    public DrawConfigPrizeStockVO() {
    }

    public DrawConfigPrizeStockVO(Long drawConfigId, String awardId) {
        this.drawConfigId = drawConfigId;
        this.awardId = awardId;
    }

    public DrawConfigPrizeStockVO(Long drawConfigId, String awardId, Long orderId) {
        this.drawConfigId = drawConfigId;
        this.awardId = awardId;
        this.orderId = orderId;
    }

    public Long getDrawConfigId() {
        return drawConfigId;
    }

    public void setDrawConfigId(Long drawConfigId) {
        this.drawConfigId = drawConfigId;
    }

    public String getAwardId() {
        return awardId;
    }

    public void setAwardId(String awardId) {
        this.awardId = awardId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    @Override
    public String toString() {
        return "DrawConfigPrizeStockVO{" +
                "drawConfigId=" + drawConfigId +
                ", awardId='" + awardId + '\'' +
                ", orderId=" + orderId +
                '}';
    }
}
