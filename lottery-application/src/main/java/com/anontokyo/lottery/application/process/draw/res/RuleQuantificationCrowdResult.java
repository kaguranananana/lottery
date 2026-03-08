package com.anontokyo.lottery.application.process.draw.res;

import com.anontokyo.lottery.common.Result;

/**
 * @description: 规则量化人群决策结果
 * @author：anontokyo
 * @date: 2026/1/25
 * 
 *        规则引擎决策后返回的活动ID结果
 */
public class RuleQuantificationCrowdResult extends Result {

    /** 决策的活动ID */
    private Long activityId;

    public RuleQuantificationCrowdResult(String code, String info) {
        super(code, info);
    }

    public RuleQuantificationCrowdResult(String code, String info, Long activityId) {
        super(code, info);
        this.activityId = activityId;
    }

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

}
