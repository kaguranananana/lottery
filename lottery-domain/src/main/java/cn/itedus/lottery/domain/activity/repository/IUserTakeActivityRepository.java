package cn.itedus.lottery.domain.activity.repository;

import cn.itedus.lottery.domain.activity.model.vo.ActivityBillVO;
import cn.itedus.lottery.domain.activity.model.vo.DrawOrderVO;
import cn.itedus.lottery.domain.activity.model.vo.UserTakeActivityVO;

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

    UserTakeActivityVO queryNoConsumedTakeActivityOrder(Long activityId, String uId);

    int lockTakeActivity(String uId, Long activityId, Long takeId);

    /**
     * 保存抽奖信息
     * @param drawOrder
     */
    void saveUserStrategyExport(DrawOrderVO drawOrder);

    void updateInvoiceMqState(String uId, Long orderId, Integer mqState);

}
