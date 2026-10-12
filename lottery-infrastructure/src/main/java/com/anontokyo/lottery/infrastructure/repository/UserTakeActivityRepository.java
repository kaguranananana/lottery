package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.UserTakeActivityVO;
import com.anontokyo.lottery.domain.activity.repository.IUserTakeActivityRepository;
import com.anontokyo.lottery.infrastructure.dao.IUserTakeActivityCountDao;
import com.anontokyo.lottery.infrastructure.dao.IUserTakeActivityDao;
import com.anontokyo.lottery.infrastructure.po.UserTakeActivity;
import com.anontokyo.lottery.infrastructure.po.UserTakeActivityCount;
import cn.bugstack.middleware.db.router.strategy.IDBRouterStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.util.Date;

@Repository
public class UserTakeActivityRepository implements IUserTakeActivityRepository {

    private final Logger logger = LoggerFactory.getLogger(UserTakeActivityRepository.class);

    @Resource
    private IUserTakeActivityCountDao userTakeActivityCountDao;

    @Resource
    private IUserTakeActivityDao userTakeActivityDao;

    @Resource
    private IDBRouterStrategy dbRouter;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Override
    public int subtractionLeftCount(ActivityBillVO bill) {
        UserTakeActivityCount userTakeActivityCount = new UserTakeActivityCount();
        userTakeActivityCount.setuId(bill.getuId());
        userTakeActivityCount.setActivityId(bill.getActivityId());
        userTakeActivityCount.setTotalCount(bill.getTakeCount());
        // 普通读取仅判断次数行是否存在；已有行避免无变化 upsert 占用主键末端间隙锁。
        if (userTakeActivityCountDao.queryUserTakeActivityCount(userTakeActivityCount) == null) {
            // 首次参与仍在同一事务内初始化，唯一约束处理并发创建，不重置已有次数。
            userTakeActivityCountDao.initializeCount(userTakeActivityCount);
        }
        // 次数耗尽时 UPDATE 匹配零行，不依赖 JDBC 是否返回匹配行数。
        return userTakeActivityCountDao.subtractionLeftCount(userTakeActivityCount);
    }

    @Override
    public void takeActivity(ActivityBillVO bill, Date partakeDate, Long takeId, String requestId) {
        UserTakeActivity userTakeActivity = new UserTakeActivity();
        userTakeActivity.setuId(bill.getuId());
        userTakeActivity.setTakeId(takeId);
        userTakeActivity.setActivityId(bill.getActivityId());
        userTakeActivity.setActivityName(bill.getActivityName());
        userTakeActivity.setTakeDate(partakeDate);
        userTakeActivity.setStrategyId(bill.getStrategyId());
        userTakeActivity.setState(Constants.TaskState.NO_USED.getCode());
        userTakeActivity.setRequestId(requestId);

        userTakeActivityDao.insert(userTakeActivity);
    }

    @Override
    public UserTakeActivityVO queryNoConsumedTakeActivityOrder(Long activityId, String uId) {
        // 查询必须成功才能认定没有未完成参与；数据库异常直接传播。
        UserTakeActivity req = new UserTakeActivity();
        req.setuId(uId);
        req.setActivityId(activityId);
        UserTakeActivity take = userTakeActivityDao.queryNoConsumedTakeActivityOrder(req);
        // 映射请求归属，不能使用过期或缺字段的 Redis 数据替代数据库事实。
        return take == null ? null : buildUserTakeActivityVO(take);
    }

    @Override
    public int lockTakeActivity(String uId, Long activityId, Long takeId) {
        UserTakeActivity userTakeActivity = new UserTakeActivity();
        userTakeActivity.setuId(uId);
        userTakeActivity.setActivityId(activityId);
        userTakeActivity.setTakeId(takeId);
        return userTakeActivityDao.lockTakeActivity(userTakeActivity);
    }

    /**
     * 扣减剩余次数 + 创建参与记录：同一路由事务。DuplicateKey（take_id / request_id 唯一冲突）
     * 返回 INDEX_DUP，由上层按幂等复用语义处理。
     */
    @Override
    public Constants.ResponseCode takeParticipation(ActivityBillVO bill, Date partakeDate, Long takeId,
            String requestId) {
        try {
            dbRouter.doRouter(bill.getuId());
            return transactionTemplate.execute(status -> {
                try {
                    int updateCount = subtractionLeftCount(bill);
                    if (updateCount == 0) {
                        status.setRollbackOnly();
                        logger.error("grab activity subtract user quota failed activityId:{} uId:{}",
                                bill.getActivityId(), bill.getuId());
                        return Constants.ResponseCode.NO_UPDATE;
                    }
                    takeActivity(bill, partakeDate, takeId, requestId);
                } catch (DuplicateKeyException e) {
                    status.setRollbackOnly();
                    logger.warn("grab activity duplicate key activityId:{} uId:{} takeId:{} requestId:{}",
                            bill.getActivityId(), bill.getuId(), takeId, requestId);
                    return Constants.ResponseCode.INDEX_DUP;
                }
                return Constants.ResponseCode.SUCCESS;
            });
        } finally {
            dbRouter.clear();
        }
    }

    @Override
    public Constants.ResponseCode consumeTake(String uId, Long activityId, Long takeId) {
        try {
            dbRouter.doRouter(uId);
            int lockCount = userTakeActivityDao.lockTakeActivity(buildLockRequest(uId, activityId, takeId));
            return lockCount > 0 ? Constants.ResponseCode.SUCCESS : Constants.ResponseCode.NO_UPDATE;
        } finally {
            dbRouter.clear();
        }
    }

    private UserTakeActivity buildLockRequest(String uId, Long activityId, Long takeId) {
        UserTakeActivity userTakeActivity = new UserTakeActivity();
        userTakeActivity.setuId(uId);
        userTakeActivity.setActivityId(activityId);
        userTakeActivity.setTakeId(takeId);
        return userTakeActivity;
    }

    @Override
    public UserTakeActivityVO queryTakeActivityByRequestId(Long activityId, String uId, String requestId) {
        UserTakeActivity req = new UserTakeActivity();
        req.setuId(uId);
        req.setActivityId(activityId);
        req.setRequestId(requestId);
        UserTakeActivity order = userTakeActivityDao.queryTakeActivityByRequestId(req);
        return order == null ? null : buildUserTakeActivityVO(order);
    }

    private UserTakeActivityVO buildUserTakeActivityVO(UserTakeActivity userTakeActivity) {
        UserTakeActivityVO userTakeActivityVO = new UserTakeActivityVO();
        userTakeActivityVO.setActivityId(userTakeActivity.getActivityId());
        userTakeActivityVO.setTakeId(userTakeActivity.getTakeId());
        userTakeActivityVO.setStrategyId(userTakeActivity.getStrategyId());
        userTakeActivityVO.setState(userTakeActivity.getState());
        // 请求号必须随记录返回，否则无法区分恢复 A 与新请求 B。
        userTakeActivityVO.setRequestId(userTakeActivity.getRequestId());
        return userTakeActivityVO;
    }
}
