package com.anontokyo.lottery.domain.activity.model.req;

import com.anontokyo.lottery.common.PageRequest;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/31
 */
public class ActivityInfoLimitPageReq extends PageRequest {

    /**
     * 活动ID
     */
    private Long activityId;

    /**
     * 活动名称
     */
    private String activityName;

    public ActivityInfoLimitPageReq(int page, int rows) {
        super(page, rows);
    }

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

    public String getActivityName() {
        return activityName;
    }

    public void setActivityName(String activityName) {
        this.activityName = activityName;
    }

}