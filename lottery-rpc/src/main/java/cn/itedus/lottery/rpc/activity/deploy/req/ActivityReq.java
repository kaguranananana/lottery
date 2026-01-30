package cn.itedus.lottery.rpc.activity.deploy.req;

import java.io.Serializable;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */
public class ActivityReq implements Serializable {

    private Long activityId;

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

}
