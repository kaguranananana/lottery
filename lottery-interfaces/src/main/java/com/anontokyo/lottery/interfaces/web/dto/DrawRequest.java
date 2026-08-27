package com.anontokyo.lottery.interfaces.web.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * @description: HTTP 抽奖请求
 * @author：anontokyo
 */
public class DrawRequest {

    @NotBlank
    @Size(max = 64)
    private String requestId;

    @NotBlank
    @Size(max = 32)
    private String userId;

    @NotNull
    private Long activityId;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
}
