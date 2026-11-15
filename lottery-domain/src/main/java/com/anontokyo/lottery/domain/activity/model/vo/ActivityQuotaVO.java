package com.anontokyo.lottery.domain.activity.model.vo;

/** 抽奖额度的只读视图。 */
public final class ActivityQuotaVO {
    public final String userId;
    public final int totalCount;
    public final int usedCount;
    public final int remainingCount;

    public ActivityQuotaVO(String userId, int totalCount, int remainingCount) {
        this.userId = userId;
        this.totalCount = totalCount;
        this.remainingCount = remainingCount;
        this.usedCount = totalCount - remainingCount;
    }
}
