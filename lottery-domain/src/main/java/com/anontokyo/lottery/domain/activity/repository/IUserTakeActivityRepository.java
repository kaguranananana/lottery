package com.anontokyo.lottery.domain.activity.repository;

import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.UserTakeActivityVO;

import java.util.Date;

/**
 * User take-activity repository.
 */
public interface IUserTakeActivityRepository {

    int subtractionLeftCount(ActivityBillVO bill);

    void takeActivity(ActivityBillVO bill, Date partakeDate, Long takeId, String requestId);

    UserTakeActivityVO queryNoConsumedTakeActivityOrderFromCache(Long activityId, String uId);

    UserTakeActivityVO queryNoConsumedTakeActivityOrder(Long activityId, String uId);

    int lockTakeActivity(String uId, Long activityId, Long takeId);

    /**
     * 按 requestId 精确查询参与记录（请求幂等核心查询，DB UNIQUE 兜底）。
     */
    UserTakeActivityVO queryTakeActivityByRequestId(Long activityId, String uId, String requestId);
}
