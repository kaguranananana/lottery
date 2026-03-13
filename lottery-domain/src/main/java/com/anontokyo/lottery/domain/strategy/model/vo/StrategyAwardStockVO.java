package com.anontokyo.lottery.domain.strategy.model.vo;

/**
 * @description: Strategy award stock MQ message object
 * @author: anontokyo
 * @date: 2026/3/9
 */
public class StrategyAwardStockVO {

    /** Strategy ID */
    private Long strategyId;

    /** Award ID */
    private String awardId;

    /** Unique business id for message deduplication */
    private Long orderId;

    public StrategyAwardStockVO() {
    }

    public StrategyAwardStockVO(Long strategyId, String awardId) {
        this.strategyId = strategyId;
        this.awardId = awardId;
    }

    public StrategyAwardStockVO(Long strategyId, String awardId, Long orderId) {
        this.strategyId = strategyId;
        this.awardId = awardId;
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

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    @Override
    public String toString() {
        return "StrategyAwardStockVO{" +
                "strategyId=" + strategyId +
                ", awardId='" + awardId + '\'' +
                ", orderId=" + orderId +
                '}';
    }
}
