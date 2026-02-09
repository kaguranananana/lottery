package cn.itedus.lottery.rpc.activity.booth.res;

import cn.itedus.lottery.common.Result;
import cn.itedus.lottery.rpc.activity.booth.dto.ActivityDTO;

import java.io.Serializable;
import java.util.List;

/**
 * @description: 活动列表响应对象
 * @author：anontokyo
 * @date: 2026/2/9
 */
public class ActivityListRes implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 响应结果 */
    private Result result;

    /** 活动列表 */
    private List<ActivityDTO> activityList;

    public ActivityListRes() {
    }

    public ActivityListRes(Result result) {
        this.result = result;
    }

    public ActivityListRes(Result result, List<ActivityDTO> activityList) {
        this.result = result;
        this.activityList = activityList;
    }

    public Result getResult() {
        return result;
    }

    public void setResult(Result result) {
        this.result = result;
    }

    public List<ActivityDTO> getActivityList() {
        return activityList;
    }

    public void setActivityList(List<ActivityDTO> activityList) {
        this.activityList = activityList;
    }
}
