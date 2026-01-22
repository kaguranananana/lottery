package cn.itedus.lottery.infrastructure.repository;

import cn.itedus.lottery.common.Constants;
import cn.itedus.lottery.domain.activity.model.req.PartakeReq;
import cn.itedus.lottery.domain.activity.model.vo.ActivityBillVO;
import cn.itedus.lottery.domain.activity.model.vo.UserTakeActivityVO;
import cn.itedus.lottery.domain.activity.repository.IUserTakeActivityRepository;
import cn.itedus.lottery.infrastructure.dao.IUserTakeActivityCountDao;
import cn.itedus.lottery.infrastructure.dao.IUserTakeActivityDao;
import cn.itedus.lottery.infrastructure.po.UserTakeActivity;
import cn.itedus.lottery.infrastructure.po.UserTakeActivityCount;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.util.Date;

/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/17
 */
@Repository
public class UserTakeActivityRepository  implements IUserTakeActivityRepository {

    @Resource
    private IUserTakeActivityCountDao userTakeActivityCountDao;

    @Resource
    private IUserTakeActivityDao userTakeActivityDao;

    @Override
    public int subtractionLeftCount(ActivityBillVO bill) {
        if (null == bill.getUserTakeLeftCount()) { // 首次参与
            // 插入记录, 初始剩余次数 = 总次数 - 1
            UserTakeActivityCount userTakeActivityCount = new UserTakeActivityCount();
            userTakeActivityCount.setuId(bill.getuId());
            userTakeActivityCount.setActivityId(bill.getActivityId());
            userTakeActivityCount.setTotalCount(bill.getTakeCount());
            userTakeActivityCount.setLeftCount(bill.getTakeCount() - 1);
            userTakeActivityCountDao.insert(userTakeActivityCount);
            return 1;
        } else {
            // 非首次参与
            UserTakeActivityCount userTakeActivityCount = new UserTakeActivityCount();
            userTakeActivityCount.setuId(bill.getuId());
            userTakeActivityCount.setActivityId(bill.getActivityId());
            return userTakeActivityCountDao.updateLeftCount(userTakeActivityCount);
        }
    }

    @Override
    public void takeActivity(ActivityBillVO bill, Date partakeDate, Long takeId) {
        UserTakeActivity userTakeActivity = new UserTakeActivity();
        userTakeActivity.setuId(bill.getuId());
        userTakeActivity.setTakeId(takeId);
        userTakeActivity.setActivityId(bill.getActivityId());
        userTakeActivity.setActivityName(bill.getActivityName());
        userTakeActivity.setTakeDate(partakeDate);

        if (null == bill.getUserTakeLeftCount()) {
            userTakeActivity.setTakeCount(1);
        } else {
            userTakeActivity.setTakeCount(bill.getTakeCount() - bill.getUserTakeLeftCount() + 1);
        }
        userTakeActivity.setStrategyId(bill.getStrategyId());
        userTakeActivity.setState(Constants.TaskState.NO_USED.getCode());
        String uuid = bill.getuId() + "_" + bill.getActivityId() + "_" + userTakeActivity.getTakeCount();
        userTakeActivity.setUuid(uuid);

        userTakeActivityDao.insert(userTakeActivity);
    }

    @Override
    public UserTakeActivityVO queryNoConsumedTakeActivityOrder(Long activityId, String uId) {
        // 用于查询
        UserTakeActivity userTakeActivity = new UserTakeActivity();
        userTakeActivity.setuId(uId);
        userTakeActivity.setActivityId(activityId);
        UserTakeActivity noConsumedTakeActivityOrder = userTakeActivityDao.queryNoConsumedTakeActivityOrder(userTakeActivity);
        // 没有查询刀符合的领取单, 直接返回null;
        if (null == noConsumedTakeActivityOrder) {
            return null;
        }

        UserTakeActivityVO userTakeActivityVO = new UserTakeActivityVO();
        userTakeActivityVO.setActivityId(noConsumedTakeActivityOrder.getActivityId());
        userTakeActivityVO.setTakeId(noConsumedTakeActivityOrder.getTakeId());
        userTakeActivityVO.setStrategyId(noConsumedTakeActivityOrder.getStrategyId());
        userTakeActivityVO.setState(noConsumedTakeActivityOrder.getState());


        return userTakeActivityVO;
    }
}
