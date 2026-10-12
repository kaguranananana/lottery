package com.anontokyo.lottery.domain.activity.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.UserTakeActivityVO;

import java.util.Date;

/**
 * User take-activity repository.
 */
public interface IUserTakeActivityRepository {

    int subtractionLeftCount(ActivityBillVO bill);

    void takeActivity(ActivityBillVO bill, Date partakeDate, Long takeId, String requestId);

    UserTakeActivityVO queryNoConsumedTakeActivityOrder(Long activityId, String uId);

    int lockTakeActivity(String uId, Long activityId, Long takeId);

    /**
     * 按 requestId 精确查询参与记录（请求幂等核心查询，DB UNIQUE 兜底）。
     */
    UserTakeActivityVO queryTakeActivityByRequestId(Long activityId, String uId, String requestId);

    /**
     * 参与活动落库：扣减用户剩余次数 + 创建参与记录在同一个路由内的事务中完成。
     * @return SUCCESS / NO_UPDATE(无剩余次数) / INDEX_DUP(take 或 request 冲突)
     */
    Constants.ResponseCode takeParticipation(ActivityBillVO bill, java.util.Date partakeDate, Long takeId,
            String requestId);

    /**
     * 消费（锁定）参与记录：state 0->1 CAS。
     * @return SUCCESS / NO_UPDATE
     */
    Constants.ResponseCode consumeTake(String uId, Long activityId, Long takeId);
}
