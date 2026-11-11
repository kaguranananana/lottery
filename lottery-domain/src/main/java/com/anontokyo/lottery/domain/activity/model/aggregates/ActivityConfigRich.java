package com.anontokyo.lottery.domain.activity.model.aggregates;

import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;
import com.anontokyo.lottery.domain.activity.model.vo.AwardVO;
import com.anontokyo.lottery.domain.activity.model.vo.DrawConfigVO;

import java.util.List;

/**
 * @description: 活动配置聚合信息
 * @author：anontokyo
 * @date: 2026/1/12
 */
public class ActivityConfigRich {

    /** 活动信息 */
    private ActivityVO activity;

    /** 策略信息(含抽奖配置奖品) */
    private DrawConfigVO drawConfig;

    /** 奖品列表 */
    private List<AwardVO> awardList;

    public ActivityConfigRich() {
    }

    public ActivityConfigRich(ActivityVO activity, DrawConfigVO drawConfig, List<AwardVO> awardList) {
        this.activity = activity;
        this.drawConfig = drawConfig;
        this.awardList = awardList;
    }

    public ActivityVO getActivity() {
        return activity;
    }

    public void setActivity(ActivityVO activity) {
        this.activity = activity;
    }

    public DrawConfigVO getDrawConfig() {
        return drawConfig;
    }

    public void setDrawConfig(DrawConfigVO drawConfig) {
        this.drawConfig = drawConfig;
    }

    public List<AwardVO> getAwardList() {
        return awardList;
    }

    public void setAwardList(List<AwardVO> awardList) {
        this.awardList = awardList;
    }
}
