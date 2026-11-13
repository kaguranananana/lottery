package com.anontokyo.lottery.domain.activity.model.vo;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/22
 */
public class DrawParticipationVO {

    /**
     * 活动领取id
     */
    private Long takeId;

    /**
     * 活动id
     */
    private Long activityId;

    /**
     * 活动单使用状态
     */
    private Integer state;

    /** 创建这条参与记录的逻辑请求，历史记录可能为空。 */
    private String requestId;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public Long getTakeId() {
        return takeId;
    }

    public void setTakeId(Long takeId) {
        this.takeId = takeId;
    }

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

    public Integer getState() {
        return state;
    }

    public void setState(Integer state) {
        this.state = state;
    }
}
