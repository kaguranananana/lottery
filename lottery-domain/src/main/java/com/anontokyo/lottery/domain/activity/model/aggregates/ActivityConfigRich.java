package com.anontokyo.lottery.domain.activity.model.aggregates;

import com.anontokyo.lottery.domain.activity.model.vo.ActivityVO;
import com.anontokyo.lottery.domain.activity.model.vo.AwardVO;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityPrizeVO;

import java.util.List;

/**
 * @description: 活动及其独立奖池
 * @author：anontokyo
 * @date: 2026/1/12
 */
public class ActivityConfigRich {

    /** 活动信息 */
    private ActivityVO activity;

    /** 本活动的奖品、概率和库存 */
    private List<ActivityPrizeVO> activityPrizeList;

    /** 新建的全局奖品；已存在的奖品直接通过奖池明细引用 */
    private List<AwardVO> awardList;

    public ActivityConfigRich() {
    }

    public ActivityConfigRich(ActivityVO activity, List<ActivityPrizeVO> activityPrizeList, List<AwardVO> awardList) {
        this.activity = activity;
        this.activityPrizeList = activityPrizeList;
        this.awardList = awardList;
    }

    public ActivityVO getActivity() {
        return activity;
    }

    public void setActivity(ActivityVO activity) {
        this.activity = activity;
    }

    public List<ActivityPrizeVO> getActivityPrizeList() {
        return activityPrizeList;
    }

    public void setActivityPrizeList(List<ActivityPrizeVO> activityPrizeList) {
        this.activityPrizeList = activityPrizeList;
    }

    public List<AwardVO> getAwardList() {
        return awardList;
    }

    public void setAwardList(List<AwardVO> awardList) {
        this.awardList = awardList;
    }
}
