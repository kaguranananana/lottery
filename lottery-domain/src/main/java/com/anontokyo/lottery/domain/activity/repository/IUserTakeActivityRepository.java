package com.anontokyo.lottery.domain.activity.repository;

import com.anontokyo.lottery.domain.activity.model.vo.*;

import java.util.Date;
import java.util.List;

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

    UserTakeActivityVO queryNoConsumedTakeActivityOrder(Long activityId, String uId);

    int lockTakeActivity(String uId, Long activityId, Long takeId);

    /**
     * 保存抽奖信息
     * @param drawOrder
     */
    void saveUserStrategyExport(DrawOrderVO drawOrder);

    void updateInvoiceMqState(String uId, Long orderId, Integer mqState);

    List<InvoiceVO> scanInvoiceMqState();

    void updateActivityStock(ActivityPartakeRecordVO activityPartakeRecordVO);
}
