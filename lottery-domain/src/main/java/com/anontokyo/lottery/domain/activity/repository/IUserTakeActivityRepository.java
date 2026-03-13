package com.anontokyo.lottery.domain.activity.repository;

import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityPartakeRecordVO;
import com.anontokyo.lottery.domain.activity.model.vo.UserTakeActivityVO;

import java.util.Date;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/17
 */
public interface IUserTakeActivityRepository {

    /**
     * 扣减个人活动参与次数
     * @param bill
     * @return
     */
    int subtractionLeftCount(ActivityBillVO bill);

    void takeActivity(ActivityBillVO bill, Date partakeDate, Long takeId);

    UserTakeActivityVO queryNoConsumedTakeActivityOrderFromCache(Long activityId, String uId);

    UserTakeActivityVO queryNoConsumedTakeActivityOrder(Long activityId, String uId);

    int lockTakeActivity(String uId, Long activityId, Long takeId);

    void updateActivityStock(ActivityPartakeRecordVO activityPartakeRecordVO);
}
