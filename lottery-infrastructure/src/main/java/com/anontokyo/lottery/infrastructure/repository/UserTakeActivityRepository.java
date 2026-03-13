package com.anontokyo.lottery.infrastructure.repository;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityPartakeRecordVO;
import com.anontokyo.lottery.domain.activity.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.activity.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.activity.model.vo.UserTakeActivityVO;
import com.anontokyo.lottery.domain.activity.repository.IUserTakeActivityRepository;
import com.anontokyo.lottery.infrastructure.cache.CacheConfigProperties;
import com.anontokyo.lottery.infrastructure.cache.TwoLevelCacheService;
import com.anontokyo.lottery.infrastructure.dao.IActivityDao;
import com.anontokyo.lottery.infrastructure.dao.IUserStrategyExportDao;
import com.anontokyo.lottery.infrastructure.dao.IUserTakeActivityCountDao;
import com.anontokyo.lottery.infrastructure.dao.IUserTakeActivityDao;
import com.anontokyo.lottery.infrastructure.po.Activity;
import com.anontokyo.lottery.infrastructure.po.UserStrategyExport;
import com.anontokyo.lottery.infrastructure.po.UserTakeActivity;
import com.anontokyo.lottery.infrastructure.po.UserTakeActivityCount;
import com.anontokyo.lottery.infrastructure.utils.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @description: user take activity repository
 * @author: anontokyo
 * @date: 2026/1/17
 */
@Repository
public class UserTakeActivityRepository implements IUserTakeActivityRepository {

    private Logger logger = LoggerFactory.getLogger(UserTakeActivityRepository.class);

    @Resource
    private IActivityDao activityDao;

    @Resource
    private IUserTakeActivityCountDao userTakeActivityCountDao;

    @Resource
    private IUserTakeActivityDao userTakeActivityDao;

    @Resource
    private IUserStrategyExportDao userStrategyExportDao;

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

        // write-first: try first-take insert, fallback to atomic decrement.
        int insertCount = userTakeActivityCountDao.insert(userTakeActivityCount);
        if (insertCount == 1) {
            return 1;
        }
        return userTakeActivityCountDao.updateLeftCount(userTakeActivityCount);
    }

    @Override
    public void takeActivity(ActivityBillVO bill, Date partakeDate, Long takeId) {
        UserTakeActivity userTakeActivity = new UserTakeActivity();
        userTakeActivity.setuId(bill.getuId());
        userTakeActivity.setTakeId(takeId);
        userTakeActivity.setActivityId(bill.getActivityId());
        userTakeActivity.setActivityName(bill.getActivityName());
        userTakeActivity.setTakeDate(partakeDate);
        userTakeActivity.setStrategyId(bill.getStrategyId());
        userTakeActivity.setState(Constants.TaskState.NO_USED.getCode());
        String uuid = bill.getuId() + "_" + bill.getActivityId() + "_" + takeId;
        userTakeActivity.setUuid(uuid);

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
        if (null == noConsumedTakeActivityOrder) {
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

    @Override
    public void saveUserStrategyExport(DrawOrderVO drawOrder) {
        UserStrategyExport userStrategyExport = new UserStrategyExport();
        userStrategyExport.setuId(drawOrder.getuId());
        userStrategyExport.setActivityId(drawOrder.getActivityId());
        userStrategyExport.setOrderId(drawOrder.getOrderId());
        userStrategyExport.setStrategyId(drawOrder.getStrategyId());
        userStrategyExport.setStrategyMode(drawOrder.getStrategyMode());
        userStrategyExport.setGrantType(drawOrder.getGrantType());
        userStrategyExport.setGrantDate(drawOrder.getGrantDate());
        userStrategyExport.setGrantState(drawOrder.getGrantState());
        userStrategyExport.setAwardId(drawOrder.getAwardId());
        userStrategyExport.setAwardType(drawOrder.getAwardType());
        userStrategyExport.setAwardName(drawOrder.getAwardName());
        userStrategyExport.setAwardContent(drawOrder.getAwardContent());
        userStrategyExport.setUuid(String.valueOf(drawOrder.getOrderId()));
        userStrategyExport.setMqState(drawOrder.getMqState());
        userStrategyExportDao.insert(userStrategyExport);
    }

    @Override
    public void updateInvoiceMqState(String uId, Long orderId, Integer mqState) {
        UserStrategyExport userStrategyExport = new UserStrategyExport();
        userStrategyExport.setuId(uId);
        userStrategyExport.setOrderId(orderId);
        userStrategyExport.setMqState(mqState);
        userStrategyExportDao.updateInvoiceMqState(userStrategyExport);
    }

    @Override
    public List<InvoiceVO> scanInvoiceMqState() {
        List<UserStrategyExport> userStrategyExportList = userStrategyExportDao.scanInvoiceMqState();
        List<InvoiceVO> invoiceVOList = new ArrayList<>(userStrategyExportList.size());
        for (UserStrategyExport userStrategyExport : userStrategyExportList) {
            InvoiceVO invoiceVO = new InvoiceVO();
            invoiceVO.setuId(userStrategyExport.getuId());
            invoiceVO.setOrderId(userStrategyExport.getOrderId());
            invoiceVO.setAwardId(userStrategyExport.getAwardId());
            invoiceVO.setAwardType(userStrategyExport.getAwardType());
            invoiceVO.setAwardName(userStrategyExport.getAwardName());
            invoiceVO.setAwardContent(userStrategyExport.getAwardContent());
            invoiceVOList.add(invoiceVO);
        }
        return invoiceVOList;
    }

    @Override
    public void updateActivityStock(ActivityPartakeRecordVO activityPartakeRecordVO) {
        Activity activity = new Activity();
        activity.setActivityId(activityPartakeRecordVO.getActivityId());
        activity.setStockSurplusCount(activityPartakeRecordVO.getStockSurplusCount());
        activityDao.updateActivityStock(activity);
        twoLevelCacheService
                .evictActivityCache(Constants.RedisKey.KEY_LOTTERY_CACHE_ACTIVITY(activityPartakeRecordVO.getActivityId()));
    }

    private void cacheNoConsumedTakeActivityOrder(UserTakeActivity userTakeActivity) {
        UserTakeActivityVO userTakeActivityVO = buildUserTakeActivityVO(userTakeActivity);
        String cacheKey = Constants.RedisKey.KEY_LOTTERY_USER_TAKE_NO_CONSUMED(userTakeActivity.getActivityId(),
                userTakeActivity.getuId());
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
