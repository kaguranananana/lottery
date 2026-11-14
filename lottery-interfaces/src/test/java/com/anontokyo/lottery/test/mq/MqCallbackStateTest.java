package com.anontokyo.lottery.test.mq;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.application.mq.consumer.AwardFulfillmentListener;
import com.anontokyo.lottery.application.mq.consumer.StockSettlementListener;
import com.anontokyo.lottery.application.mq.producer.RocketMqProducer;
import com.anontokyo.lottery.application.mq.producer.OutboxDispatcher;
import com.anontokyo.lottery.common.Constants.AwardState;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository.OutboxKind;
import com.anontokyo.lottery.domain.award.service.fulfillment.AwardFulfillmentHandler;
import com.anontokyo.lottery.domain.award.service.fulfillment.AwardFulfillmentResult;
import com.anontokyo.lottery.domain.draw.repository.IActivityPrizeRepository;
import org.junit.After;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MqCallbackStateTest {
    private final IOrderRepository orders = mock(IOrderRepository.class);
    private final RocketMqProducer producer = mock(RocketMqProducer.class);
    private final OutboxDispatcher outbox = new OutboxDispatcher(orders, producer, 1, 1, 1);

    @After
    public void shutdown() {
        outbox.close();
    }

    @Test
    public void payloadKeepsOrderIdentityWithoutAliasingPoolId() {
        AwardStockSettlementVO stock = JSON.parseObject(
                "{\"uId\":\"mq-test\",\"orderId\":7,\"strategyId\":42}", AwardStockSettlementVO.class);
        assertEquals("mq-test", stock.getuId());
        assertEquals(Long.valueOf(7L), stock.getOrderId());
        assertNull(stock.getActivityId());
        stock.setActivityId(57L);
        com.alibaba.fastjson.JSONObject payload = JSON.parseObject(JSON.toJSONString(stock));
        assertEquals(Long.valueOf(57L), payload.getLong("activityId"));
        assertFalse(payload.containsKey("strategyId"));
        AwardFulfillmentEvent award = JSON.parseObject(
                "{\"uId\":\"mq-test\",\"orderId\":7,\"grantDate\":1893456000000}", AwardFulfillmentEvent.class);
        assertEquals(Long.valueOf(7L), award.getOrderId());
        assertEquals("mq-test", award.getuId());
        assertFalse(JSON.parseObject(JSON.toJSONString(award)).containsKey("grantDate"));
    }

    @Test
    public void compensationSendsOnlyAfterClaim() {
        DrawOrderVO candidate = order(0, 0, 0);
        when(orders.scanOutbox(OutboxKind.STOCK, 1)).thenReturn(Collections.singletonList(candidate));
        when(orders.claimOutbox(OutboxKind.STOCK, 1L, 600)).thenReturn(true);
        outbox.recover(OutboxKind.STOCK);
        verify(producer, timeout(2000)).sendStockSettlement(any());
    }

    @Test
    public void invoiceConsumerWritesBeforeReturning() {
        when(orders.findOrder("mq-test", 1L)).thenReturn(order(1, 0, 0));
        AwardFulfillmentHandler handler = mock(AwardFulfillmentHandler.class);
        when(handler.supportType()).thenReturn(3);
        when(handler.fulfill(any())).thenReturn(new AwardFulfillmentResult("mq-test", AwardState.SUCCESS, "成功", true));
        invoiceListener(handler).onMessage(invoiceRecord());
        verify(handler).fulfill(any());
    }

    @Test
    public void duplicateGrantedInvoiceRepairsHistoricalMqBeforeReturning() {
        when(orders.findOrder("mq-test", 1L)).thenReturn(order(1, 1, 0));
        AwardFulfillmentHandler handler = mock(AwardFulfillmentHandler.class);
        invoiceListener(handler).onMessage(invoiceRecord());
        verify(orders).updateFulfillmentEventStatus("mq-test", 1L, 1);
        verify(handler, never()).fulfill(any());
    }

    @Test
    public void grantSqlErrorPropagates() {
        when(orders.findOrder("mq-test", 1L)).thenReturn(order(1, 0, 0));
        AwardFulfillmentHandler handler = mock(AwardFulfillmentHandler.class);
        when(handler.supportType()).thenReturn(3);
        when(handler.fulfill(any())).thenThrow(new IllegalStateException("DB unavailable"));
        assertThrows(IllegalStateException.class,
                () -> invoiceListener(handler).onMessage(invoiceRecord()));
    }

    @Test
    public void stockGapPersistsReconciliationBeforeReturning() {
        when(orders.findOrder("mq-test", 1L)).thenReturn(order(0, 0, 0));
        IActivityPrizeRepository config = mock(IActivityPrizeRepository.class);
        stockListener(config).onMessage(stockRecord());
        verify(orders).markStockSettlementFailed("mq-test", 1L);
        verify(orders, never()).updateStockSettlementEventStatus(anyString(), anyLong(), anyInt());
    }

    @Test
    public void stockGapPersistenceErrorPropagates() {
        when(orders.findOrder("mq-test", 1L)).thenReturn(order(0, 0, 0));
        doThrow(new IllegalStateException("DB unavailable")).when(orders).markStockSettlementFailed("mq-test", 1L);
        assertThrows(IllegalStateException.class, () -> stockListener(mock(IActivityPrizeRepository.class))
                .onMessage(stockRecord()));
    }

    @Test
    public void blockedStockCannotBeRetriedByBrokerDuplicate() {
        when(orders.findOrder("mq-test", 1L)).thenReturn(order(4, 0, 0));
        IActivityPrizeRepository config = mock(IActivityPrizeRepository.class);
        stockListener(config).onMessage(stockRecord());
        verify(config, never()).settleAwardStock(any());
    }

    @Test
    public void onlyStockTransitionWinnerQueuesFulfillment() {
        DrawOrderVO pending = order(0, 0, 0);
        when(orders.findOrder("mq-test", 1L)).thenReturn(pending);
        when(orders.updateStockSettlementEventStatus("mq-test", 1L, 1)).thenReturn(true, false);
        IActivityPrizeRepository config = mock(IActivityPrizeRepository.class);
        when(config.settleAwardStock(any())).thenReturn(true);
        // 两次消费都读到旧状态，只有赢得库存 CAS 的消费者能触发发奖首发。
        stockListener(config).onMessage(stockRecord());
        stockListener(config).onMessage(stockRecord());
        assertEquals(Long.valueOf(0), outbox.metrics().get("rejected"));
        verify(producer, timeout(2000)).sendAwardFulfillment(any());
    }

    private AwardFulfillmentListener invoiceListener(AwardFulfillmentHandler handler) {
        AwardFulfillmentListener listener = new AwardFulfillmentListener();
        ReflectionTestUtils.setField(listener, "orderRepository", orders);
        ReflectionTestUtils.setField(listener, "fulfillmentHandlers", Collections.singletonList(handler));
        listener.initRegistry();
        return listener;
    }

    private StockSettlementListener stockListener(IActivityPrizeRepository config) {
        StockSettlementListener listener = new StockSettlementListener();
        ReflectionTestUtils.setField(listener, "orderRepository", orders);
        ReflectionTestUtils.setField(listener, "activityPrizeRepository", config);
        ReflectionTestUtils.setField(listener, "outbox", outbox);
        return listener;
    }

    private AwardFulfillmentEvent invoiceRecord() {
        AwardFulfillmentEvent event = new AwardFulfillmentEvent();
        event.setuId("mq-test");
        event.setOrderId(1L);
        return event;
    }

    private AwardStockSettlementVO stockRecord() {
        return stock(1L);
    }

    private AwardStockSettlementVO stock(long id) {
        AwardStockSettlementVO stock = new AwardStockSettlementVO();
        stock.setuId("mq-test");
        stock.setOrderId(id);
        return stock;
    }

    private DrawOrderVO order(int stock, int award, int fulfillment) {
        DrawOrderVO order = new DrawOrderVO();
        order.setOrderId(1L);
        order.setuId("mq-test");
        order.setAwardType(3);
        order.setStockSettlementEventStatus(stock);
        order.setAwardFulfillmentStatus(award);
        order.setFulfillmentEventStatus(fulfillment);
        return order;
    }
}
