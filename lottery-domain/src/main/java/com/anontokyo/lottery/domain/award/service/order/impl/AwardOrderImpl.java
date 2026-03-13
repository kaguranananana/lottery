package com.anontokyo.lottery.domain.award.service.order.impl;

import cn.bugstack.middleware.db.router.strategy.IDBRouterStrategy;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.common.Result;
import com.anontokyo.lottery.domain.award.model.req.AwardOrderReq;
import com.anontokyo.lottery.domain.award.model.res.AwardOrderResult;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.activity.repository.IUserTakeActivityRepository;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
import com.anontokyo.lottery.domain.strategy.repository.IStrategyRepository;
import com.anontokyo.lottery.domain.support.ids.IIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 奖品订单领域服务实现，负责中奖单持久化与奖品库存结算。
 */
@Service
public class AwardOrderImpl implements IAwardOrder {

    private final Logger logger = LoggerFactory.getLogger(AwardOrderImpl.class);

    @Resource
    private IDBRouterStrategy dbRouter;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private IUserTakeActivityRepository userTakeActivityRepository;

    @Resource
    private IOrderRepository orderRepository;

    @Resource
    private IStrategyRepository strategyRepository;

    @Resource
    private Map<Constants.Ids, IIdGenerator> idGeneratorMap;

    @Override
    public AwardOrderResult placeOrder(AwardOrderReq awardOrderReq) {
        DrawOrderVO drawOrder = buildDrawOrder(awardOrderReq);
        boolean deductSuccess = strategyRepository.deductStock(drawOrder.getStrategyId(), drawOrder.getAwardId());
        if (!deductSuccess) {
            strategyRepository.recoverStrategyAwardStockByRedis(drawOrder.getStrategyId(), drawOrder.getAwardId());
            return buildFailResult("奖品库存扣减失败");
        }

        try {
            dbRouter.doRouter(drawOrder.getuId());
            Result result = transactionTemplate.execute(status -> {
                try {
                    int lockCount = userTakeActivityRepository.lockTakeActivity(
                            drawOrder.getuId(), drawOrder.getActivityId(), drawOrder.getTakeId());
                    if (lockCount == 0) {
                        status.setRollbackOnly();
                        logger.error("记录中奖单，个人参与活动抽奖已消耗完 activityId：{} uId：{}",
                                drawOrder.getActivityId(), drawOrder.getuId());
                        return Result.buildResult(Constants.ResponseCode.NO_UPDATE);
                    }

                    orderRepository.saveDrawOrder(drawOrder);
                } catch (DuplicateKeyException e) {
                    status.setRollbackOnly();
                    logger.error("记录中奖单，唯一索引冲突 activityId：{} uId：{}",
                            drawOrder.getActivityId(), drawOrder.getuId(), e);
                    return Result.buildResult(Constants.ResponseCode.INDEX_DUP);
                }

                return Result.buildSuccessResult();
            });
            if (result == null) {
                recoverAwardStock(drawOrder);
                return buildFailResult("记录中奖单失败");
            }
            if (!Constants.ResponseCode.SUCCESS.getCode().equals(result.getCode())) {
                recoverAwardStock(drawOrder);
                return new AwardOrderResult(result.getCode(), result.getInfo());
            }
            return new AwardOrderResult(Constants.ResponseCode.SUCCESS.getCode(),
                    Constants.ResponseCode.SUCCESS.getInfo(), buildInvoice(drawOrder));
        } catch (RuntimeException e) {
            recoverAwardStock(drawOrder);
            throw e;
        } finally {
            dbRouter.clear();
        }
    }

    @Override
    public void updateInvoiceMqState(String uId, Long orderId, Integer mqState) {
        orderRepository.updateInvoiceMqState(uId, orderId, mqState);
    }

    @Override
    public List<InvoiceVO> scanInvoiceMqState(int dbCount, int tbCount) {
        try {
            dbRouter.setDBKey(dbCount);
            dbRouter.setTBKey(tbCount);
            return orderRepository.scanInvoiceMqState();
        } finally {
            dbRouter.clear();
        }
    }

    private void recoverAwardStock(DrawOrderVO drawOrder) {
        strategyRepository.recoverStock(drawOrder.getStrategyId(), drawOrder.getAwardId());
        strategyRepository.recoverStrategyAwardStockByRedis(drawOrder.getStrategyId(), drawOrder.getAwardId());
    }

    private DrawOrderVO buildDrawOrder(AwardOrderReq awardOrderReq) {
        DrawOrderVO drawOrderVO = new DrawOrderVO();
        drawOrderVO.setuId(awardOrderReq.getuId());
        drawOrderVO.setTakeId(awardOrderReq.getTakeId());
        drawOrderVO.setActivityId(awardOrderReq.getActivityId());
        drawOrderVO.setOrderId(idGeneratorMap.get(Constants.Ids.SnowFlake).nextId());
        drawOrderVO.setStrategyId(awardOrderReq.getStrategyId());
        drawOrderVO.setStrategyMode(awardOrderReq.getStrategyMode());
        drawOrderVO.setGrantType(awardOrderReq.getGrantType());
        drawOrderVO.setGrantDate(awardOrderReq.getGrantDate());
        drawOrderVO.setGrantState(Constants.GrantState.INIT.getCode());
        drawOrderVO.setAwardId(awardOrderReq.getAwardId());
        drawOrderVO.setAwardType(awardOrderReq.getAwardType());
        drawOrderVO.setAwardName(awardOrderReq.getAwardName());
        drawOrderVO.setAwardContent(awardOrderReq.getAwardContent());
        drawOrderVO.setMqState(Constants.MQState.INIT.getCode());
        return drawOrderVO;
    }

    private InvoiceVO buildInvoice(DrawOrderVO drawOrderVO) {
        InvoiceVO invoiceVO = new InvoiceVO();
        invoiceVO.setuId(drawOrderVO.getuId());
        invoiceVO.setOrderId(drawOrderVO.getOrderId());
        invoiceVO.setAwardId(drawOrderVO.getAwardId());
        invoiceVO.setAwardType(drawOrderVO.getAwardType());
        invoiceVO.setAwardName(drawOrderVO.getAwardName());
        invoiceVO.setAwardContent(drawOrderVO.getAwardContent());
        return invoiceVO;
    }

    private AwardOrderResult buildFailResult(String info) {
        return new AwardOrderResult(Constants.ResponseCode.UN_ERROR.getCode(), info);
    }
}
