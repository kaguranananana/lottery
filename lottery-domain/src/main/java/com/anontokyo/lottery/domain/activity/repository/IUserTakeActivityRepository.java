package com.anontokyo.lottery.domain.activity.repository;

import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityPartakeRecordVO;
import com.anontokyo.lottery.domain.activity.model.vo.UserTakeActivityVO;

import java.util.Date;

/**
 * User take-activity repository.
 */
public interface IUserTakeActivityRepository {

    int subtractionLeftCount(ActivityBillVO bill);

    void takeActivity(ActivityBillVO bill, Date partakeDate, Long takeId);

    UserTakeActivityVO queryNoConsumedTakeActivityOrderFromCache(Long activityId, String uId);

    UserTakeActivityVO queryNoConsumedTakeActivityOrder(Long activityId, String uId);

    int lockTakeActivity(String uId, Long activityId, Long takeId);

    void updateActivityStock(ActivityPartakeRecordVO activityPartakeRecordVO);
}
