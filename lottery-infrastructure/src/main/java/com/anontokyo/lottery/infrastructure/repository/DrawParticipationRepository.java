package com.anontokyo.lottery.infrastructure.repository;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.DrawParticipationVO;
import com.anontokyo.lottery.domain.activity.repository.IDrawParticipationRepository;
import com.anontokyo.lottery.infrastructure.dao.IUserActivityQuotaDao;
import com.anontokyo.lottery.infrastructure.dao.IDrawParticipationDao;
import com.anontokyo.lottery.infrastructure.po.DrawParticipation;
import com.anontokyo.lottery.infrastructure.po.UserActivityQuota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.util.Date;

@Repository
public class DrawParticipationRepository implements IDrawParticipationRepository {

    private final Logger logger = LoggerFactory.getLogger(DrawParticipationRepository.class);

    @Resource
    private IUserActivityQuotaDao userActivityQuotaDao;

    @Resource
    private IDrawParticipationDao drawParticipationDao;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Override
    public int subtractionLeftCount(ActivityBillVO bill) {
        UserActivityQuota userActivityQuota = new UserActivityQuota();
        userActivityQuota.setuId(bill.getuId());
        userActivityQuota.setActivityId(bill.getActivityId());
        userActivityQuota.setTotalCount(bill.getTakeCount());
        // 普通读取仅判断次数行是否存在；已有行避免无变化 upsert 占用主键末端间隙锁。
        if (userActivityQuotaDao.queryUserActivityQuota(userActivityQuota) == null) {
            // 首次参与仍在同一事务内初始化，唯一约束处理并发创建，不重置已有次数。
            userActivityQuotaDao.initializeCount(userActivityQuota);
        }
        // 次数耗尽时 UPDATE 匹配零行，不依赖 JDBC 是否返回匹配行数。
        return userActivityQuotaDao.subtractionLeftCount(userActivityQuota);
    }

    @Override
    public void takeActivity(ActivityBillVO bill, Date participatedAt, Long takeId, String requestId) {
        DrawParticipation drawParticipation = new DrawParticipation();
        drawParticipation.setuId(bill.getuId());
        drawParticipation.setTakeId(takeId);
        drawParticipation.setActivityId(bill.getActivityId());

        drawParticipation.setParticipatedAt(participatedAt);
        drawParticipation.setState(Constants.TaskState.NO_USED.getCode());
        drawParticipation.setRequestId(requestId);

        drawParticipationDao.insert(drawParticipation);
    }

    @Override
    public DrawParticipationVO queryUnfinishedParticipation(Long activityId, String uId) {
        // 查询必须成功才能认定没有未完成参与；数据库异常直接传播。
        DrawParticipation req = new DrawParticipation();
        req.setuId(uId);
        req.setActivityId(activityId);
        DrawParticipation take = drawParticipationDao.queryUnfinishedParticipation(req);
        // 映射请求归属，不能使用过期或缺字段的 Redis 数据替代数据库事实。
        return take == null ? null : toParticipation(take);
    }

    @Override
    public int consumeParticipation(String uId, Long activityId, Long takeId) {
        DrawParticipation drawParticipation = new DrawParticipation();
        drawParticipation.setuId(uId);
        drawParticipation.setActivityId(activityId);
        drawParticipation.setTakeId(takeId);
        return drawParticipationDao.consumeParticipation(drawParticipation);
    }

    /**
     * 扣减剩余次数 + 创建参与记录：同一数据库事务。DuplicateKey（take_id / request_id 唯一冲突）
     * 返回 INDEX_DUP，由上层按幂等复用语义处理。
     */
    @Override
    public Constants.ResponseCode takeParticipation(ActivityBillVO bill, Date participatedAt, Long takeId,
            String requestId) {
        // TX1 仅包含用户次数和参与记录，不涉及 Redis 库存或其它 Repository。
        return transactionTemplate.execute(status -> {
            try {
                int updateCount = subtractionLeftCount(bill);
                if (updateCount == 0) {
                    status.setRollbackOnly();
                    logger.error("grab activity subtract user quota failed activityId:{} uId:{}",
                            bill.getActivityId(), bill.getuId());
                    return Constants.ResponseCode.NO_UPDATE;
                }
                takeActivity(bill, participatedAt, takeId, requestId);
            } catch (DuplicateKeyException e) {
                status.setRollbackOnly();
                logger.warn("grab activity duplicate key activityId:{} uId:{} takeId:{} requestId:{}",
                        bill.getActivityId(), bill.getuId(), takeId, requestId);
                return Constants.ResponseCode.INDEX_DUP;
            }
            return Constants.ResponseCode.SUCCESS;
        });
    }

    @Override
    public Constants.ResponseCode consumeTake(String uId, Long activityId, Long takeId) {
        int lockCount = drawParticipationDao.consumeParticipation(buildLockRequest(uId, activityId, takeId));
        return lockCount > 0 ? Constants.ResponseCode.SUCCESS : Constants.ResponseCode.NO_UPDATE;
    }

    private DrawParticipation buildLockRequest(String uId, Long activityId, Long takeId) {
        DrawParticipation drawParticipation = new DrawParticipation();
        drawParticipation.setuId(uId);
        drawParticipation.setActivityId(activityId);
        drawParticipation.setTakeId(takeId);
        return drawParticipation;
    }

    @Override
    public DrawParticipationVO queryParticipationByRequestId(Long activityId, String uId, String requestId) {
        DrawParticipation req = new DrawParticipation();
        req.setuId(uId);
        req.setActivityId(activityId);
        req.setRequestId(requestId);
        DrawParticipation order = drawParticipationDao.queryParticipationByRequestId(req);
        return order == null ? null : toParticipation(order);
    }

    private DrawParticipationVO toParticipation(DrawParticipation drawParticipation) {
        DrawParticipationVO drawParticipationVO = new DrawParticipationVO();
        drawParticipationVO.setActivityId(drawParticipation.getActivityId());
        drawParticipationVO.setTakeId(drawParticipation.getTakeId());
        drawParticipationVO.setState(drawParticipation.getState());
        // 请求号必须随记录返回，否则无法区分恢复 A 与新请求 B。
        drawParticipationVO.setRequestId(drawParticipation.getRequestId());
        return drawParticipationVO;
    }
}
