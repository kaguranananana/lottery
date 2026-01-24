package cn.itedus.lottery.rpc.req;

import java.io.Serializable;

/**
 * @description: 普通抽奖请求对象
 * @author：anontokyo
 * @date: 2026/1/25
 */
public class DrawReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private String uId;

    /** 活动ID */
    private Long activityId;

    public DrawReq() {
    }

    public DrawReq(String uId, Long activityId) {
        this.uId = uId;
        this.activityId = activityId;
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

}
