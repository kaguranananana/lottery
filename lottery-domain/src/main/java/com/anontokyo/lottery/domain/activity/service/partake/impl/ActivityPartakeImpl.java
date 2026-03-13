package com.anontokyo.lottery.domain.activity.service.partake.impl;

import cn.bugstack.middleware.db.router.strategy.IDBRouterStrategy;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.res.StockResult;
import com.anontokyo.lottery.domain.activity.model.vo.*;
import com.anontokyo.lottery.domain.activity.repository.IUserTakeActivityRepository;
import com.anontokyo.lottery.domain.activity.service.partake.BaseActivityPartake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.util.List;

/**
 * @description: 活动参与功能的业务实现
 * @author：anontokyo
 * @date: 2026/1/17
 */
@Service
public class ActivityPartakeImpl extends BaseActivityPartake {
    private Logger logger = LoggerFactory.getLogger(ActivityPartakeImpl.class);

    @Resource
    private IDBRouterStrategy dbRouter;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private IUserTakeActivityRepository userTakeActivityRepository;



    @Override
    protected Result checkActivityBill(PartakeReq partake, ActivityBillVO bill) {
        // 校验 活动状态
        if (!Constants.ActivityState.DOING.getCode().equals(bill.getState())) {
            logger.warn("活动当前状态非可用 state：{}", bill.getState());
            return Result.buildResult(Constants.ResponseCode.UN_ERROR, "活动当前状态非可用");
        }

        // 校验: 活动日期
        if (bill.getBeginDateTime().after(partake.getPartakeDate()) || bill.getEndDateTime().before(partake.getPartakeDate())) {
            logger.warn("活动时间范围非可用 beginDateTime：{} endDateTime：{}", bill.getBeginDateTime(), bill.getEndDateTime());
            return Result.buildResult(Constants.ResponseCode.UN_ERROR, "活动时间范围非可用");
        }
        // 校验：活动库存
        if (bill.getStockSurplusCount() <= 0) {
            logger.warn("活动剩余库存非可用 stockSurplusCount：{}", bill.getStockSurplusCount());
            return Result.buildResult(Constants.ResponseCode.UN_ERROR, "活动剩余库存非可用");
        }
        return Result.buildSuccessResult();
    }

    @Override
    protected Result substractionActivityStock(PartakeReq req) {
        int count = activityRepository.substractionActivityStock(req.getActivityId());
        if (0 == count) {
            logger.error("扣减活动库存失败 activityId：{}", req.getActivityId());
            return Result.buildResult(Constants.ResponseCode.NO_UPDATE);
        }
        return Result.buildSuccessResult();
    }



    @Override
    protected Result grabActivity(PartakeReq partake, ActivityBillVO bill, Long takeId) {
        try {
            dbRouter.doRouter(partake.getuId());
            return transactionTemplate.execute(transactionStatus -> {
                try {
                    // 扣减个人剩余参与次数
                    int updateCount = userTakeActivityRepository.subtractionLeftCount(bill);
                    if (updateCount == 0) {
                        transactionStatus.setRollbackOnly();
                        logger.error("领取活动，扣减个人已参与次数失败 activityId：{} uId：{}", partake.getActivityId(), partake.getuId());
                        return Result.buildResult(Constants.ResponseCode.NO_UPDATE);
                    }

                    // 插入领取活动信息
                    userTakeActivityRepository.takeActivity(bill, partake.getPartakeDate(), takeId);


                } catch (DuplicateKeyException e) {
                    transactionStatus.setRollbackOnly();
                    logger.error("领取活动，唯一索引冲突 activityId：{} uId：{}", partake.getActivityId(), partake.getuId(), e);
                    return Result.buildResult(Constants.ResponseCode.INDEX_DUP);
                }

                return Result.buildSuccessResult();
            });
        } finally {
            dbRouter.clear();
        }
    }




    @Override
    protected UserTakeActivityVO queryNoConsumedTakeActivityOrderFromCache(Long activityId, String uId) {
        return userTakeActivityRepository.queryNoConsumedTakeActivityOrderFromCache(activityId, uId);
    }

    @Override
    protected UserTakeActivityVO queryNoConsumedTakeActivityOrder(Long activityId, String uId) {
        return userTakeActivityRepository.queryNoConsumedTakeActivityOrder(activityId, uId);
    }

    @Override
    public Result consumeTakeActivity(String uId, Long activityId, Long takeId) {
        try {
            dbRouter.doRouter(uId);
            int lockCount = userTakeActivityRepository.lockTakeActivity(uId, activityId, takeId);
            if (lockCount == 0) {
                logger.error("consume take activity failed, no row updated activityId:{} uId:{} takeId:{}", activityId,
                        uId, takeId);
                return Result.buildResult(Constants.ResponseCode.NO_UPDATE);
            }
            return Result.buildSuccessResult();
        } finally {
            dbRouter.clear();
        }
    }

    @Override
    public Result recordDrawOrder(DrawOrderVO drawOrder) {
        try {
            dbRouter.doRouter(drawOrder.getuId());
            return transactionTemplate.execute(status -> {
                try {
                    // 锁定活动领取记录
                   int lockCount = userTakeActivityRepository.lockTakeActivity(drawOrder.getuId(), drawOrder.getActivityId(), drawOrder.getTakeId());
                   if (lockCount == 0) {
                       status.setRollbackOnly();
                       logger.error("记录中奖单，个人参与活动抽奖已消耗完 activityId：{} uId：{}", drawOrder.getActivityId(), drawOrder.getuId());
                       return Result.buildResult(Constants.ResponseCode.NO_UPDATE);
                   }

                   // 保存抽奖信息
                    userTakeActivityRepository.saveUserStrategyExport(drawOrder);
                } catch (DuplicateKeyException e) {
                    status.setRollbackOnly();
                    logger.error("记录中奖单，唯一索引冲突 activityId：{} uId：{}", drawOrder.getActivityId(), drawOrder.getuId(), e);
                    return Result.buildResult(Constants.ResponseCode.INDEX_DUP);
                }

                return Result.buildSuccessResult();
            });
        } finally {
            dbRouter.clear();
        }
    }

    @Override
    public void updateInvoiceMqState(String uId, Long orderId, Integer mqState) {
        userTakeActivityRepository.updateInvoiceMqState(uId, orderId, mqState);
    }

    @Override
    public List<InvoiceVO> scanInvoiceMqState(int dbCount, int tbCount) {
        try {
            // 设置路由
            dbRouter.setDBKey(dbCount);
            dbRouter.setTBKey(tbCount);

            // 查询数据
            return userTakeActivityRepository.scanInvoiceMqState();
        } finally {
            dbRouter.clear();
        }
    }



    @Override
    protected StockResult substractionActivityStockByRedis(String uId, Long activityId) {
        return activityRepository.substractionActivityStockByRedis(uId, activityId);
    }

    @Override
    protected void recoverActivityStockByRedis(Long activityId) {
        activityRepository.recoverActivityStockByRedis(activityId);
    }

    @Override
    protected void recoverActivityCacheStockByRedis(String stockKey) {
        activityRepository.recoverActivityCacheStockByRedis(stockKey);
    }

    @Override
    public void updateActivityStock(ActivityPartakeRecordVO activityPartakeRecordVO) {
        userTakeActivityRepository.updateActivityStock(activityPartakeRecordVO);
    }
}
