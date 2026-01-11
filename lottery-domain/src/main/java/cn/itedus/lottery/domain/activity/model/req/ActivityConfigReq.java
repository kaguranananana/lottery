package cn.itedus.lottery.domain.activity.model.req;

import cn.itedus.lottery.domain.activity.model.aggregates.ActivityConfigRich;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/12
 */
public class ActivityConfigReq {
    private Long activityId;

    private ActivityConfigRich activityConfigRich;

    public ActivityConfigReq(Long activityId, ActivityConfigRich activityConfigRich) {
        this.activityId = activityId;
        this.activityConfigRich = activityConfigRich;
    }

    public ActivityConfigRich getActivityConfigRich() {
        return activityConfigRich;
    }

    public void setActivityConfigRich(ActivityConfigRich activityConfigRich) {
        this.activityConfigRich = activityConfigRich;
    }

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }
}
