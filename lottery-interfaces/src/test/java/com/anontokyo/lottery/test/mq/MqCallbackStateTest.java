package com.anontokyo.lottery.test.mq;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.application.mq.consumer.AwardFulfillmentListener;
import com.anontokyo.lottery.application.mq.consumer.StockSettlementListener;
import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
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
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.After;
import org.junit.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MqCallbackStateTest {
    private final IOrderRepository orders = mock(IOrderRepository.class);
    private final KafkaProducer producer = mock(KafkaProducer.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final OutboxDispatcher outbox = new OutboxDispatcher(orders, producer, 1, 1, 45, 600, 100, 1, true);

    @After
    public void shutdown() {
        outbox.close();
    }

    @Test
    public void legacyKafkaPayloadKeepsOrderIdentityWithoutAliasingPoolId() {
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
    public void realtimeUsesPersistedGenerationWithoutClaimAndKeepsInflightBounded() {
        SettableListenableFuture<SendResult<String, Object>> future = new SettableListenableFuture<>();
        when(producer.sendStockSettlement(any())).thenReturn(future);
        outbox.afterOrderCommit(stock(1L));
        verify(producer, timeout(2000)).sendStockSettlement(any());
        outbox.afterOrderCommit(stock(2L));
        // 订单提交时已登记代数，实时发送不访问数据库；第二笔因在途额度保留在 Outbox。
        verifyNoInteractions(orders);
        assertEquals(Long.valueOf(1), outbox.metrics().get("rejected"));
        verify(orders, never()).finishOutbox(any(), anyLong(), anyInt(), anyBoolean(), anyInt());
        future.set(null);
        verify(orders, timeout(2000)).finishOutbox(OutboxKind.STOCK, 1L, 1, true, 600);
    }

    @Test
    public void compensationUsesScannedGenerationWithoutPerOrderRead() {
        DrawOrderVO candidate = order(0, 0, 0);
        candidate.setStockAttempt(2);
        when(orders.scanOutbox(OutboxKind.STOCK, 1)).thenReturn(Collections.singletonList(candidate));
        when(orders.claimOutbox(OutboxKind.STOCK, 1L, 2, 45)).thenReturn(true);
        SettableListenableFuture<SendResult<String, Object>> future = new SettableListenableFuture<>();
        when(producer.sendStockSettlement(any())).thenReturn(future);
        java.util.concurrent.CompletableFuture<Void> scan = java.util.concurrent.CompletableFuture.runAsync(
                () -> outbox.recover(OutboxKind.STOCK));
        verify(producer, timeout(2000)).sendStockSettlement(any());
        verify(orders, never()).findOrder(anyString(), anyLong());
        future.setException(new IllegalStateException("Kafka unavailable"));
        verify(orders, timeout(2000)).finishOutbox(OutboxKind.STOCK, 1L, 3, false, 20);
        scan.join();
    }

    @Test
    public void fulfillmentRecoveryUsesSameClaimAndResultPath() {
        DrawOrderVO candidate = order(1, 0, 2);
        when(orders.scanOutbox(OutboxKind.FULFILLMENT, 1)).thenReturn(Collections.singletonList(candidate));
        when(orders.claimOutbox(OutboxKind.FULFILLMENT, 1L, 0, 45)).thenReturn(true);
        SettableListenableFuture<SendResult<String, Object>> future = new SettableListenableFuture<>();
        future.set(null);
        when(producer.sendAwardFulfillment(any())).thenReturn(future);
        outbox.recover(OutboxKind.FULFILLMENT);
        verify(orders, timeout(2000)).finishOutbox(OutboxKind.FULFILLMENT, 1L, 1, true, 600);
    }

    @Test
    public void invoiceConsumerWritesBeforeAck() {
        when(orders.findOrder("mq-test", 1L)).thenReturn(order(1, 0, 3));
        AwardFulfillmentHandler handler = mock(AwardFulfillmentHandler.class);
        when(handler.supportType()).thenReturn(3);
        when(handler.fulfill(any())).thenReturn(new AwardFulfillmentResult("mq-test", AwardState.SUCCESS, "成功", true));
        invoiceListener(handler).onMessage(invoiceRecord(), ack, "lottery_invoice");
        org.mockito.InOrder sequence = inOrder(handler, ack);
        sequence.verify(handler).fulfill(any());
        sequence.verify(ack).acknowledge();
    }

    @Test
    public void duplicateGrantedInvoiceRepairsHistoricalMqBeforeAck() {
        when(orders.findOrder("mq-test", 1L)).thenReturn(order(1, 1, 3));
        AwardFulfillmentHandler handler = mock(AwardFulfillmentHandler.class);
        invoiceListener(handler).onMessage(invoiceRecord(), ack, "lottery_invoice");
        org.mockito.InOrder sequence = inOrder(orders, ack);
        sequence.verify(orders).updateFulfillmentEventStatus("mq-test", 1L, 1);
        sequence.verify(ack).acknowledge();
        verify(handler, never()).fulfill(any());
    }

    @Test
    public void grantSqlErrorPreventsAck() {
        when(orders.findOrder("mq-test", 1L)).thenReturn(order(1, 0, 3));
        AwardFulfillmentHandler handler = mock(AwardFulfillmentHandler.class);
        when(handler.supportType()).thenReturn(3);
        when(handler.fulfill(any())).thenThrow(new IllegalStateException("DB unavailable"));
        assertThrows(IllegalStateException.class,
                () -> invoiceListener(handler).onMessage(invoiceRecord(), ack, "lottery_invoice"));
        verify(ack, never()).acknowledge();
    }

    @Test
    public void stockGapPersistsReconciliationBeforeAck() {
        when(orders.findOrder("mq-test", 1L)).thenReturn(order(0, 0, 0));
        IActivityPrizeRepository config = mock(IActivityPrizeRepository.class);
        stockListener(config).onMessage(stockRecord(), ack, "lottery_strategy_award_stock");
        org.mockito.InOrder sequence = inOrder(orders, ack);
        sequence.verify(orders).markStockSettlementFailed("mq-test", 1L);
        sequence.verify(ack).acknowledge();
        verify(orders, never()).updateStockSettlementEventStatus(anyString(), anyLong(), anyInt());
    }

    @Test
    public void stockGapPersistenceErrorPreventsAck() {
        when(orders.findOrder("mq-test", 1L)).thenReturn(order(0, 0, 0));
        doThrow(new IllegalStateException("DB unavailable")).when(orders).markStockSettlementFailed("mq-test", 1L);
        assertThrows(IllegalStateException.class, () -> stockListener(mock(IActivityPrizeRepository.class))
                .onMessage(stockRecord(), ack, "lottery_strategy_award_stock"));
        verify(ack, never()).acknowledge();
    }

    @Test
    public void blockedStockCannotBeRetriedByBrokerDuplicate() {
        when(orders.findOrder("mq-test", 1L)).thenReturn(order(4, 0, 0));
        IActivityPrizeRepository config = mock(IActivityPrizeRepository.class);
        stockListener(config).onMessage(stockRecord(), ack, "lottery_strategy_award_stock");
        verify(config, never()).settleAwardStock(any());
        verify(ack).acknowledge();
    }

    @Test
    public void onlyStockTransitionWinnerQueuesFulfillment() {
        DrawOrderVO pending = order(0, 0, 0);
        pending.setFulfillmentAttempt(2);
        when(orders.findOrder("mq-test", 1L)).thenReturn(pending);
        when(orders.updateStockSettlementEventStatus("mq-test", 1L, 1)).thenReturn(true, false);
        IActivityPrizeRepository config = mock(IActivityPrizeRepository.class);
        when(config.settleAwardStock(any())).thenReturn(true);
        SettableListenableFuture<SendResult<String, Object>> future = new SettableListenableFuture<>();
        when(producer.sendAwardFulfillment(any())).thenReturn(future);
        // 两次消费都读到旧状态，只有赢得库存 CAS 的消费者能触发发奖首发。
        stockListener(config).onMessage(stockRecord(), ack, "lottery_strategy_award_stock");
        stockListener(config).onMessage(stockRecord(), ack, "lottery_strategy_award_stock");
        assertEquals(Long.valueOf(0), outbox.metrics().get("rejected"));
        verify(producer, timeout(2000)).sendAwardFulfillment(any());
        future.set(null);
        verify(orders, timeout(2000)).finishOutbox(OutboxKind.FULFILLMENT, 1L, 3, true, 600);
        verify(ack, times(2)).acknowledge();
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

    private ConsumerRecord<String, String> invoiceRecord() {
        return new ConsumerRecord<>("lottery_invoice", 0, 0, "1", "{\"uId\":\"mq-test\",\"orderId\":1}");
    }

    private ConsumerRecord<String, String> stockRecord() {
        return new ConsumerRecord<>("lottery_strategy_award_stock", 0, 0, "1", JSON.toJSONString(stock(1L)));
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
