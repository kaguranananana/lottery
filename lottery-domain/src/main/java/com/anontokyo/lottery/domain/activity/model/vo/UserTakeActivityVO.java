package com.anontokyo.lottery.domain.activity.model.vo;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/22
 */
public class UserTakeActivityVO {

    /**
     * 活动领取id
     */
    private Long takeId;



    /**
     * 策略ID
     */
    private Long strategyId;

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

    public Long getStrategyId() {
        return strategyId;
    }

    public void setStrategyId(Long strategyId) {
        this.strategyId = strategyId;
    }

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
