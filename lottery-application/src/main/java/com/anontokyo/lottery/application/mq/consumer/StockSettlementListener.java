package com.anontokyo.lottery.application.mq.consumer;

import com.anontokyo.lottery.application.mq.producer.OutboxDispatcher;
import com.anontokyo.lottery.common.Constants.AwardFulfillmentStatus;
import com.anontokyo.lottery.common.Constants.StockSettlementEventStatus;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.draw.repository.IActivityPrizeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/** 库存扣减与唯一账本同事务，重复消息以持久化事实结算。 */
@Component
public class StockSettlementListener {
    private final Logger logger = LoggerFactory.getLogger(StockSettlementListener.class);

    @Resource
    private IActivityPrizeRepository activityPrizeRepository;

    @Resource
    private IOrderRepository orderRepository;

    @Resource
    private OutboxDispatcher outbox;

    public void onMessage(AwardStockSettlementVO message) {
        DrawOrderVO order = orderRepository.findOrder(message.getuId(), message.getOrderId());
        if (order == null) {
            logger.warn("stock event order missing user:{} order:{}", message.getuId(), message.getOrderId());
            return;
        }
        Integer state = order.getStockSettlementEventStatus();
        if (StockSettlementEventStatus.COMPLETE.getCode().equals(state)
                || StockSettlementEventStatus.BLOCKED.getCode().equals(state)) {
            // 阻断订单只能由对账恢复。迟到或重复消息不重新尝试扣减。
            return;
        }

        // 由订单快照决定被扣减的奖品，旧消息的 strategyId 和新消息的 activityId 都不作为路由输入。
        AwardStockSettlementVO settlement = new AwardStockSettlementVO();
        BeanUtils.copyProperties(order, settlement);
        if (!activityPrizeRepository.settleAwardStock(settlement)) {
            orderRepository.markStockSettlementFailed(order.getuId(), order.getOrderId());
            logger.error("stock reconciliation required user:{} order:{} activity:{} award:{}",
                    order.getuId(), order.getOrderId(), order.getActivityId(), order.getAwardId());
            return;
        }

        // 只有推进库存终态的消费者取得发奖首发资格，并在同一 SQL 中登记补偿时间。
        if (orderRepository.updateStockSettlementEventStatus(order.getuId(), order.getOrderId(),
                StockSettlementEventStatus.COMPLETE.getCode())
                && !AwardFulfillmentStatus.COMPLETE.getCode().equals(order.getAwardFulfillmentStatus())) {
            AwardFulfillmentEvent award = new AwardFulfillmentEvent();
            BeanUtils.copyProperties(order, award);
            outbox.afterStockSettled(award);
        }
    }
}
