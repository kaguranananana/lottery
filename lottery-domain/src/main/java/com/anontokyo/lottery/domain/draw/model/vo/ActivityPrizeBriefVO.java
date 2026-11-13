package com.anontokyo.lottery.domain.draw.model.vo;

import java.math.BigDecimal;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */
public class ActivityPrizeBriefVO {

    // 自增ID
    private String id;

    // 活动ID
    private Long activityId;

    // 奖品ID
    private String awardId;

    // 总库存沿持久化读取到活动奖池快照保持 INT 语义，不进行字符串转换。
    private Integer awardCount;

    // 中奖概率
    private BigDecimal awardRate;

    public Integer getAwardCount() {
        return awardCount;
    }

    public void setAwardCount(Integer awardCount) {
        this.awardCount = awardCount;
    }

    public String getAwardId() {
        return awardId;
    }

    public void setAwardId(String awardId) {
        this.awardId = awardId;
    }

    public BigDecimal getAwardRate() {
        return awardRate;
    }

    public void setAwardRate(BigDecimal awardRate) {
        this.awardRate = awardRate;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }
}
