package cn.itedus.lottery.domain.activity.repository;

import cn.itedus.lottery.domain.activity.model.req.PartakeReq;
import cn.itedus.lottery.domain.activity.model.vo.ActivityBillVO;

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
}
