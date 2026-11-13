package com.anontokyo.lottery.domain.activity.model.vo;

import java.util.Date;

/**
 * @description: 活动账单
 * @author：anontokyo
 * @date: 2026/1/17
 */
public class ActivityBillVO {
    /** 用户ID */
    private String uId;

    /** 活动ID */
    private Long activityId;
    /** 开始时间 */
    private Date beginDateTime;
    /** 结束时间 */
    private Date endDateTime;
    /**
     * 活动状态：1草稿、4就绪、5运行、7关闭
     * Constants.ActivityState
     */
    private Integer state;

    /** 每人可参与次数 */
    private Integer takeCount;
    /** 剩余可领取次数 */
    private Integer userTakeLeftCount;

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

    public Date getBeginDateTime() {
        return beginDateTime;
    }

    public void setBeginDateTime(Date beginDateTime) {
        this.beginDateTime = beginDateTime;
    }

    public Date getEndDateTime() {
        return endDateTime;
    }

    public void setEndDateTime(Date endDateTime) {
        this.endDateTime = endDateTime;
    }

    public Integer getState() {
        return state;
    }

    public void setState(Integer state) {
        this.state = state;
    }

    public Integer getTakeCount() {
        return takeCount;
    }

    public void setTakeCount(Integer takeCount) {
        this.takeCount = takeCount;
    }

    public Integer getUserTakeLeftCount() {
        return userTakeLeftCount;
    }

    public void setUserTakeLeftCount(Integer userTakeLeftCount) {
        this.userTakeLeftCount = userTakeLeftCount;
    }

    @Override
    public String toString() {
        return "ActivityBillVO{" +
                "uId='" + uId + '\'' +
                ", activityId=" + activityId +
                ", beginDateTime=" + beginDateTime +
                ", endDateTime=" + endDateTime +
                ", state=" + state +
                ", takeCount=" + takeCount +
                ", userTakeLeftCount=" + userTakeLeftCount +
                '}';
    }
}
