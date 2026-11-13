package com.anontokyo.lottery.domain.award.service.order.impl;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.req.AwardOrderReq;
import com.anontokyo.lottery.domain.award.model.res.AwardOrderResult;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
import com.anontokyo.lottery.domain.support.ids.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 中奖订单领域服务：锁定参与记录 + 创建订单必须在一个数据库事务内完成（Outbox 写入点），
 * 事务细节由 infrastructure 仓储实现承担。
 */
@Service
public class AwardOrderImpl implements IAwardOrder {

    private final Logger logger = LoggerFactory.getLogger(AwardOrderImpl.class);

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
                    buildInvoice(drawOrder));
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
    public AwardFulfillmentEvent queryFulfillmentEventByOrderId(String uId, Long orderId) {
        return orderRepository.queryFulfillmentEventByOrderId(uId, orderId);
    }

    private DrawOrderVO buildDrawOrder(AwardOrderReq awardOrderReq) {
        DrawOrderVO drawOrderVO = new DrawOrderVO();
        drawOrderVO.setuId(awardOrderReq.getuId());
        drawOrderVO.setTakeId(awardOrderReq.getTakeId());
        drawOrderVO.setActivityId(awardOrderReq.getActivityId());
        drawOrderVO.setOrderId(idGenerator.nextId());
        drawOrderVO.setAwardFulfillmentStatus(Constants.AwardFulfillmentStatus.INIT.getCode());
        drawOrderVO.setAwardId(awardOrderReq.getAwardId());
        drawOrderVO.setAwardType(awardOrderReq.getAwardType());
        drawOrderVO.setAwardName(awardOrderReq.getAwardName());
        drawOrderVO.setAwardContent(awardOrderReq.getAwardContent());
        drawOrderVO.setFulfillmentEventStatus(Constants.FulfillmentEventStatus.INIT.getCode());
        drawOrderVO.setStockSettlementEventStatus(Constants.StockSettlementEventStatus.INIT.getCode());
        return drawOrderVO;
    }

    private AwardFulfillmentEvent buildInvoice(DrawOrderVO drawOrderVO) {
        AwardFulfillmentEvent fulfillmentEvent = new AwardFulfillmentEvent();
        fulfillmentEvent.setuId(drawOrderVO.getuId());
        fulfillmentEvent.setOrderId(drawOrderVO.getOrderId());
        fulfillmentEvent.setTakeId(drawOrderVO.getTakeId());
        fulfillmentEvent.setAwardId(drawOrderVO.getAwardId());
        fulfillmentEvent.setAwardType(drawOrderVO.getAwardType());
        fulfillmentEvent.setAwardName(drawOrderVO.getAwardName());
        fulfillmentEvent.setAwardContent(drawOrderVO.getAwardContent());
        return fulfillmentEvent;
    }
}
