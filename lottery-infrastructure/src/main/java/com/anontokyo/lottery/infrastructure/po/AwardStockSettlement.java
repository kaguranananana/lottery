package com.anontokyo.lottery.infrastructure.po;

import java.util.Date;

/**
 * 奖品库存结算幂等账本（全局库 lottery，与 strategy_detail 同库、同事务）。
 * order_id 唯一约束是「同一个订单只允许扣一次数据库库存」的最终事实来源；
 * Kafka 重复投递、消费者 crash 重放都会在此被 duplicate-key 拦截。
 */
public class AwardStockSettlement {

    private Long id;

    private Long orderId;

    private String uId;

    private Long strategyId;

    private String awardId;

    private Date createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getuId() {
        return uId;
    }

    public void setuId(String uId) {
        this.uId = uId;
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

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}
