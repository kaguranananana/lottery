package com.anontokyo.lottery.domain.strategy.model.vo;

/**
 * @description: 策略奖品库存MQ消息体, 用于Redis扣减后异步落库
 * @author：anontokyo
 * @date: 2026/3/9
 */
public class StrategyAwardStockVO {

    /** 策略ID */
    private Long strategyId;

    /** 奖品ID */
    private String awardId;

    public StrategyAwardStockVO() {
    }

    public StrategyAwardStockVO(Long strategyId, String awardId) {
        this.strategyId = strategyId;
        this.awardId = awardId;
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
        return "StrategyAwardStockVO{" +
                "strategyId=" + strategyId +
                ", awardId='" + awardId + '\'' +
                '}';
    }
}

