package com.anontokyo.lottery.domain.draw.model.res;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.draw.model.vo.DrawAwardInfo;

/**
 * @description: 抽奖结果
 * @author：anontokyo
 * @date: 2026/1/12
 */

/**
 * @description: 抽奖结果
 * @author：小傅哥，微信：fustack
 * @date: 2021/8/28
 * @Copyright：公众号：bugstack虫洞栈 | 博客：https://bugstack.cn - 沉淀、分享、成长，让自己和他人都能有所收获！
 */
public class DrawResult {

    /**
     * 用户ID
     */
    private String uId;

    /**
     * 活动ID
     */
    private Long activityId;

    /**
     * 中奖状态：0未中奖、1已中奖、2兜底奖 Constants.DrawState
     */
    private Integer drawState = Constants.DrawState.FAIL.getCode();

    /**
     * 中奖奖品信息
     */
    private DrawAwardInfo drawAwardInfo;

    public DrawResult() {
    }

    public DrawResult(String uId, Long activityId, Integer drawState) {
        this.uId = uId;
        this.activityId = activityId;
        this.drawState = drawState;
    }

    public DrawResult(String uId, Long activityId, Integer drawState, DrawAwardInfo drawAwardInfo) {
        this.uId = uId;
        this.activityId = activityId;
        this.drawState = drawState;
        this.drawAwardInfo = drawAwardInfo;
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

    public Integer getDrawState() {
        return drawState;
    }

    public void setDrawState(Integer drawState) {
        this.drawState = drawState;
    }

    public DrawAwardInfo getDrawAwardInfo() {
        return drawAwardInfo;
    }

    public void setDrawAwardInfo(DrawAwardInfo drawAwardInfo) {
        this.drawAwardInfo = drawAwardInfo;
    }

}
