package com.anontokyo.lottery.infrastructure.po;

import java.util.Date;

/**
 * @description: 用户策略计算结果表
 * @author：anontokyo
 * @date: 2026/1/16
 */
public class DrawOrder {
    /** 自增ID */
    private Long id;
    /** 用户ID */
    private String uId;
    /** 活动ID */
    private Long activityId;
    /** 订单ID */
    private Long orderId;
    /** 关联的用户参与记录ID（幂等回放用） */
    private Long takeId;
    /** 抽奖配置ID */
    private Long drawConfigId;
    /** 发放奖品方式（1:即时、2:定时[含活动结束]、3:人工） */
    private Integer grantType;
    /** 发奖时间 */
    private Date scheduledAt;

    /** 实际完成时间由发奖 CAS 写入。 */
    private Date fulfilledAt;

    public Date getFulfilledAt() {
        return fulfilledAt;
    }

    public void setFulfilledAt(Date fulfilledAt) {
        this.fulfilledAt = fulfilledAt;
    }
    /** 发奖状态 */
    private Integer awardFulfillmentStatus;
    /** 发奖ID */
    private String awardId;
    /** 奖品类型（1:文字描述、2:兑换码、3:优惠券、4:实物奖品） */
    private Integer awardType;
    /** 奖品名称 */
    private String awardName;
    /** 奖品内容「文字描述、Key、码」 */
    private String awardContent;
    /** 发奖事件状态（0待投递、1消费完成、2投递失败、3已投递） */
    private Integer fulfillmentEventStatus;
    /** Award stock settlement MQ state. */
    private Integer stockSettlementEventStatus;
    /** 创建时间 */
    private Date createTime;
    /** 更新时间 */
    private Date updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getuId() {
        return uId;
    }

    public void setuId(String uId) {
        this.uId = uId;
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

    public Long getTakeId() {
        return takeId;
    }

    public void setTakeId(Long takeId) {
        this.takeId = takeId;
    }

    public Long getDrawConfigId() {
        return drawConfigId;
    }

    public void setDrawConfigId(Long drawConfigId) {
        this.drawConfigId = drawConfigId;
    }

    public Integer getGrantType() {
        return grantType;
    }

    public void setGrantType(Integer grantType) {
        this.grantType = grantType;
    }

    public Date getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Date scheduledAt) {
        this.scheduledAt = scheduledAt;
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

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }
    // 投递代数由数据库 CAS 管理，随扫描候选读取。
    private int stockAttempt;
    private int fulfillmentAttempt;

    public int getStockAttempt() { return stockAttempt; }
    public void setStockAttempt(int stockAttempt) { this.stockAttempt = stockAttempt; }
    public int getFulfillmentAttempt() { return fulfillmentAttempt; }
    public void setFulfillmentAttempt(int fulfillmentAttempt) { this.fulfillmentAttempt = fulfillmentAttempt; }
}
