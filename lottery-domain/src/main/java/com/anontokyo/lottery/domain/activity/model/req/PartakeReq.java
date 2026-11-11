package com.anontokyo.lottery.domain.activity.model.req;

import java.util.Date;

/**
 * @description: 参与活动请求
 * @author：anontokyo
 * @date: 2026/1/17
 */
public class PartakeReq {
    /** 用户ID */
    private String uId;
    /** 活动ID */
    private Long activityId;
    /** 请求幂等号（透传自 Draw API） */
    private String requestId;
    /** 活动领取时间 */
    private Date participatedAt;

    public PartakeReq() {
    }

    public PartakeReq(String uId, Long activityId) {
        this.uId = uId;
        this.activityId = activityId;
        this.participatedAt = new Date();
    }

    public PartakeReq(String uId, Long activityId, String requestId) {
        this.uId = uId;
        this.activityId = activityId;
        this.requestId = requestId;
        this.participatedAt = new Date();
    }

    public PartakeReq(String uId, Long activityId, Date participatedAt) {
        this.uId = uId;
        this.activityId = activityId;
        this.participatedAt = participatedAt;
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

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Date getParticipatedAt() {
        return participatedAt;
    }

    public void setParticipatedAt(Date participatedAt) {
        this.participatedAt = participatedAt;
    }

}
