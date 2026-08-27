package com.anontokyo.lottery.application.process.draw.req;

/**
 * @description: 抽奖请求
 * @author：anontokyo
 * @date：2026/1/22
 */
public class DrawProcessReq {
    /** 用户ID */
    private String uId;
    /** 活动ID */
    private Long activityId;
    /** 请求幂等号：客户端生成，同一 requestId 重试返回同一业务结果 */
    private String requestId;

    public DrawProcessReq() {
    }

    public DrawProcessReq(String uId, Long activityId, String requestId) {
        this.uId = uId;
        this.activityId = activityId;
        this.requestId = requestId;
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
}
