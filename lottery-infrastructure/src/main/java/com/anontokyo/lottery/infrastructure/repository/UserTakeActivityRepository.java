package com.anontokyo.lottery.infrastructure.repository;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.UserTakeActivityVO;
import com.anontokyo.lottery.domain.activity.repository.IUserTakeActivityRepository;
import com.anontokyo.lottery.infrastructure.cache.CacheConfigProperties;
import com.anontokyo.lottery.infrastructure.cache.TwoLevelCacheService;
import com.anontokyo.lottery.infrastructure.dao.IActivityDao;
import com.anontokyo.lottery.infrastructure.dao.IUserTakeActivityCountDao;
import com.anontokyo.lottery.infrastructure.dao.IUserTakeActivityDao;
import com.anontokyo.lottery.infrastructure.po.Activity;
import com.anontokyo.lottery.infrastructure.po.UserTakeActivity;
import com.anontokyo.lottery.infrastructure.po.UserTakeActivityCount;
import cn.bugstack.middleware.db.router.strategy.IDBRouterStrategy;
import com.anontokyo.lottery.infrastructure.utils.RedisUtil;
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
    private IActivityDao activityDao;

    @Resource
    private IUserTakeActivityCountDao userTakeActivityCountDao;

    @Resource
    private IUserTakeActivityDao userTakeActivityDao;

    @Resource
    private IDBRouterStrategy dbRouter;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private CacheConfigProperties cacheConfigProperties;

    @Resource
    private TwoLevelCacheService twoLevelCacheService;

    @Override
    public int subtractionLeftCount(ActivityBillVO bill) {
        UserTakeActivityCount userTakeActivityCount = new UserTakeActivityCount();
        userTakeActivityCount.setuId(bill.getuId());
        userTakeActivityCount.setActivityId(bill.getActivityId());
        userTakeActivityCount.setTotalCount(bill.getTakeCount());
        userTakeActivityCount.setLeftCount(bill.getTakeCount() - 1);
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
        cacheNoConsumedTakeActivityOrder(userTakeActivity);
    }

    @Override
    public UserTakeActivityVO queryNoConsumedTakeActivityOrderFromCache(Long activityId, String uId) {
        String cacheKey = Constants.RedisKey.KEY_LOTTERY_USER_TAKE_NO_CONSUMED(activityId, uId);
        try {
            String cacheValue = redisUtil.get(cacheKey);
            if (cacheValue != null && cacheValue.length() > 0) {
                return JSON.parseObject(cacheValue, UserTakeActivityVO.class);
            }
        } catch (Exception e) {
            logger.warn("query no consumed order cache failed, cacheKey:{}", cacheKey, e);
        }
        return null;
    }

    @Override
    public UserTakeActivityVO queryNoConsumedTakeActivityOrder(Long activityId, String uId) {
        String cacheKey = Constants.RedisKey.KEY_LOTTERY_USER_TAKE_NO_CONSUMED(activityId, uId);
        UserTakeActivityVO cachedOrder = queryNoConsumedTakeActivityOrderFromCache(activityId, uId);
        if (cachedOrder != null) {
            return cachedOrder;
        }

        UserTakeActivity userTakeActivity = new UserTakeActivity();
        userTakeActivity.setuId(uId);
        userTakeActivity.setActivityId(activityId);
        UserTakeActivity noConsumedTakeActivityOrder = userTakeActivityDao.queryNoConsumedTakeActivityOrder(userTakeActivity);
        if (noConsumedTakeActivityOrder == null) {
            return null;
        }

        UserTakeActivityVO userTakeActivityVO = buildUserTakeActivityVO(noConsumedTakeActivityOrder);
        try {
            redisUtil.setWithExpire(cacheKey, JSON.toJSONString(userTakeActivityVO),
                    cacheConfigProperties.getNoConsumedOrderRedisTtlSeconds());
        } catch (Exception e) {
            logger.warn("set no consumed order cache failed, cacheKey:{}", cacheKey, e);
        }
        return userTakeActivityVO;
    }

    @Override
    public int lockTakeActivity(String uId, Long activityId, Long takeId) {
        UserTakeActivity userTakeActivity = new UserTakeActivity();
        userTakeActivity.setuId(uId);
        userTakeActivity.setActivityId(activityId);
        userTakeActivity.setTakeId(takeId);
        int lockCount = userTakeActivityDao.lockTakeActivity(userTakeActivity);
        if (lockCount > 0) {
            try {
                redisUtil.delStringKey(Constants.RedisKey.KEY_LOTTERY_USER_TAKE_NO_CONSUMED(activityId, uId));
            } catch (Exception e) {
                logger.warn("delete no consumed order cache failed, uId:{} activityId:{}", uId, activityId, e);
            }
        }
        return lockCount;
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

    private void cacheNoConsumedTakeActivityOrder(UserTakeActivity userTakeActivity) {
        UserTakeActivityVO userTakeActivityVO = buildUserTakeActivityVO(userTakeActivity);
        String cacheKey = Constants.RedisKey.KEY_LOTTERY_USER_TAKE_NO_CONSUMED(
                userTakeActivity.getActivityId(), userTakeActivity.getuId());
        try {
            redisUtil.setWithExpire(cacheKey, JSON.toJSONString(userTakeActivityVO),
                    cacheConfigProperties.getNoConsumedOrderRedisTtlSeconds());
        } catch (Exception e) {
            logger.warn("cache no consumed order failed, cacheKey:{}", cacheKey, e);
        }
    }

    private UserTakeActivityVO buildUserTakeActivityVO(UserTakeActivity userTakeActivity) {
        UserTakeActivityVO userTakeActivityVO = new UserTakeActivityVO();
        userTakeActivityVO.setActivityId(userTakeActivity.getActivityId());
        userTakeActivityVO.setTakeId(userTakeActivity.getTakeId());
        userTakeActivityVO.setStrategyId(userTakeActivity.getStrategyId());
        userTakeActivityVO.setState(userTakeActivity.getState());
        return userTakeActivityVO;
    }
}
