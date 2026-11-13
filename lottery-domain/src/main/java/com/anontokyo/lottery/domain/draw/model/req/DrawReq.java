package com.anontokyo.lottery.domain.draw.model.req;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */
public class DrawReq {

    // 用户ID
    private String uId;

    // 活动ID
    private Long activityId;

    public DrawReq() {
    }

    public DrawReq(String uId, Long activityId) {
        this.uId = uId;
        this.activityId = activityId;
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

}
