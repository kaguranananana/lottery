package com.anontokyo.lottery.application.mq.consumer;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.service.fulfillment.AwardFulfillmentHandler;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import org.springframework.beans.BeanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 发奖消费者。Spring 注入全部 {@link AwardFulfillmentHandler} 后按奖品类型构建注册表，
 * 不再需要 Config+Factory 的双层包装。
 */
@Component
public class AwardFulfillmentListener {

    private final Logger logger = LoggerFactory.getLogger(AwardFulfillmentListener.class);

    @Resource
    private List<AwardFulfillmentHandler> fulfillmentHandlers;

    @Resource
    private IOrderRepository orderRepository;

    private final Map<Integer, AwardFulfillmentHandler> handlerRegistry = new HashMap<>();

    @PostConstruct
    public void initRegistry() {
        for (AwardFulfillmentHandler handler : fulfillmentHandlers) {
            AwardFulfillmentHandler previous = handlerRegistry.put(handler.supportType(), handler);
            if (previous != null) {
                throw new IllegalStateException("duplicate award fulfillment handler for type "
                        + handler.supportType());
            }
        }
        logger.info("award fulfillment handlers registered: {}", handlerRegistry.keySet());
    }

    public void onMessage(AwardFulfillmentEvent message) {
        DrawOrderVO order = orderRepository.findOrder(message.getuId(), message.getOrderId());
        if (order == null) {
            logger.warn("fulfillment event order missing user:{} order:{}", message.getuId(), message.getOrderId());
            return;
        }
        if (!Constants.StockSettlementEventStatus.COMPLETE.getCode().equals(order.getStockSettlementEventStatus())) {
            // 旧消息可能在库存完成前抵达，后续库存 CAS 会持久登记发奖义务。
            return;
        }
        if (Constants.AwardFulfillmentStatus.COMPLETE.getCode().equals(order.getAwardFulfillmentStatus())) {
            if (!Constants.FulfillmentEventStatus.COMPLETE.getCode().equals(order.getFulfillmentEventStatus())) {
                // 已经有历史发奖事实时只修复消息状态。
                orderRepository.updateFulfillmentEventStatus(order.getuId(), order.getOrderId(),
                        Constants.FulfillmentEventStatus.COMPLETE.getCode());
            }
            return;
        }

        AwardFulfillmentHandler handler = handlerRegistry.get(order.getAwardType());
        if (handler == null) {
            throw new IllegalStateException("unsupported award type " + order.getAwardType() + ", order " + order.getOrderId());
        }
        // 当前四种处理器只写本地 CAS。多个消费者可能并发到达，由本地发奖 CAS 收口。
        AwardFulfillmentEvent event = new AwardFulfillmentEvent();
        BeanUtils.copyProperties(order, event);
        Constants.AwardState state = handler.fulfill(event).getState();
        if (state != Constants.AwardState.SUCCESS) {
            throw new IllegalStateException("award fulfillment failed, order " + order.getOrderId() + ": " + state.getInfo());
        }
    }
}
