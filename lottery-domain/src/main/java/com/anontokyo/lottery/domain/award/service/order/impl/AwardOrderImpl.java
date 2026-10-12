package com.anontokyo.lottery.domain.award.service.order.impl;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.repository.IUserTakeActivityRepository;
import com.anontokyo.lottery.domain.award.model.req.AwardOrderReq;
import com.anontokyo.lottery.domain.award.model.res.AwardOrderResult;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
import com.anontokyo.lottery.domain.support.ids.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * 中奖订单领域服务：锁定参与记录 + 创建订单必须在一个数据库事务内完成（Outbox 写入点），
 * 路由与事务细节由 infrastructure 仓储实现承担。
 */
@Service
public class AwardOrderImpl implements IAwardOrder {

    private final Logger logger = LoggerFactory.getLogger(AwardOrderImpl.class);

    @Resource
    private IUserTakeActivityRepository userTakeActivityRepository;

    @Resource
    private IOrderRepository orderRepository;

    @Resource
    private IdGenerator idGenerator;

    @Override
    public AwardOrderResult placeOrder(AwardOrderReq awardOrderReq) {
        DrawOrderVO drawOrder = buildDrawOrder(awardOrderReq);

        Constants.ResponseCode code = orderRepository.saveDrawOrderWithTakeLock(drawOrder);
        if (code == Constants.ResponseCode.SUCCESS) {
            AwardOrderResult successResult = new AwardOrderResult(
                    Constants.ResponseCode.SUCCESS.getCode(),
                    Constants.ResponseCode.SUCCESS.getInfo(),
                    buildInvoice(drawOrder),
                    buildAwardStockSettlement(drawOrder));
            if (logger.isDebugEnabled()) {
                logger.debug("award order placed uId:{} activityId:{} orderId:{} awardId:{}", drawOrder.getuId(),
                        drawOrder.getActivityId(), drawOrder.getOrderId(), drawOrder.getAwardId());
            }
            return successResult;
        }

        logger.warn("award order failed uId:{} activityId:{} takeId:{} orderId:{} code:{}",
                drawOrder.getuId(), drawOrder.getActivityId(), drawOrder.getTakeId(), drawOrder.getOrderId(), code);
        return new AwardOrderResult(code.getCode(), code.getInfo());
    }

    @Override
    public void updateInvoiceMqState(String uId, Long orderId, Integer mqState) {
        orderRepository.updateInvoiceMqState(uId, orderId, mqState);
    }

    @Override
    public void updateStockMqState(String uId, Long orderId, Integer stockMqState) {
        orderRepository.updateStockMqState(uId, orderId, stockMqState);
    }

    @Override
    public void markStockSettlementFailed(String uId, Long orderId) {
        // 消费失败事件独立透传，不能复用发送失败的状态条件。
        orderRepository.markStockSettlementFailed(uId, orderId);
    }

    @Override
    public List<InvoiceVO> scanInvoiceMqState(int dbCount, int tbCount, int limit) {
        return orderRepository.scanInvoiceMqStateByShard(dbCount, tbCount, limit);
    }

    @Override
    public List<AwardStockSettlementVO> scanAwardStockMqState(int dbCount, int tbCount, int limit) {
        return orderRepository.scanAwardStockMqStateByShard(dbCount, tbCount, limit);
    }

    @Override
    public InvoiceVO queryInvoiceByOrderId(String uId, Long orderId) {
        return orderRepository.queryInvoiceByOrderId(uId, orderId);
    }

    @Override
    public boolean isStockMqCompleted(String uId, Long orderId) {
        return orderRepository.isStockMqCompleted(uId, orderId);
    }

    @Override
    public int shardingDbCount() {
        return orderRepository.shardingDbCount();
    }

    @Override
    public int shardingTableCount() {
        return orderRepository.shardingTableCount();
    }

    private DrawOrderVO buildDrawOrder(AwardOrderReq awardOrderReq) {
        DrawOrderVO drawOrderVO = new DrawOrderVO();
        drawOrderVO.setuId(awardOrderReq.getuId());
        drawOrderVO.setTakeId(awardOrderReq.getTakeId());
        drawOrderVO.setActivityId(awardOrderReq.getActivityId());
        drawOrderVO.setOrderId(idGenerator.nextId());
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
        drawOrderVO.setStockMqState(Constants.MQState.INIT.getCode());
        return drawOrderVO;
    }

    private InvoiceVO buildInvoice(DrawOrderVO drawOrderVO) {
        InvoiceVO invoiceVO = new InvoiceVO();
        invoiceVO.setuId(drawOrderVO.getuId());
        invoiceVO.setOrderId(drawOrderVO.getOrderId());
        invoiceVO.setTakeId(drawOrderVO.getTakeId());
        invoiceVO.setAwardId(drawOrderVO.getAwardId());
        invoiceVO.setAwardType(drawOrderVO.getAwardType());
        invoiceVO.setAwardName(drawOrderVO.getAwardName());
        invoiceVO.setAwardContent(drawOrderVO.getAwardContent());
        return invoiceVO;
    }

    private AwardStockSettlementVO buildAwardStockSettlement(DrawOrderVO drawOrderVO) {
        AwardStockSettlementVO settlementVO = new AwardStockSettlementVO();
        settlementVO.setuId(drawOrderVO.getuId());
        settlementVO.setOrderId(drawOrderVO.getOrderId());
        settlementVO.setStrategyId(drawOrderVO.getStrategyId());
        settlementVO.setAwardId(drawOrderVO.getAwardId());
        return settlementVO;
    }
}
