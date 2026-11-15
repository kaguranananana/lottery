package com.anontokyo.lottery.infrastructure.po;

/** 单个库存桶；初始与剩余数量始终处于 [0, initialCount]。 */
public class AwardStockBucket {
    private Long activityId;
    private String awardId;
    private Integer bucketId;
    private Integer initialCount;
    private Integer remainingCount;

    public AwardStockBucket(Long activityId, String awardId, Integer bucketId, Integer initialCount) {
        this.activityId = activityId;
        this.awardId = awardId;
        this.bucketId = bucketId;
        this.initialCount = initialCount;
        this.remainingCount = initialCount;
    }
    public Long getActivityId() { return activityId; }
    public String getAwardId() { return awardId; }
    public Integer getBucketId() { return bucketId; }
    public Integer getInitialCount() { return initialCount; }
    public Integer getRemainingCount() { return remainingCount; }
}
