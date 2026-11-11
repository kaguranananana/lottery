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
import com.anontokyo.lottery.domain.draw.repository.IDrawConfigRepository;
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
    public void legacyKafkaFieldsRoundTripWithNewJavaNames() {
        AwardStockSettlementVO stock = JSON.parseObject(
                "{\"orderId\":7,\"strategyId\":42}", AwardStockSettlementVO.class);
        assertEquals(Long.valueOf(42L), stock.getDrawConfigId());
        assertTrue(JSON.parseObject(JSON.toJSONString(stock)).containsKey("strategyId"));
        AwardFulfillmentEvent award = JSON.parseObject(
                "{\"orderId\":7,\"grantDate\":1893456000000}", AwardFulfillmentEvent.class);
        assertEquals(1893456000000L, award.getScheduledAt().getTime());
        assertTrue(JSON.parseObject(JSON.toJSONString(award)).containsKey("grantDate"));
    }

    @Test
    public void realtimeSendsOnlyAfterClaimAndCallbackKeepsInflightBounded() {
        SettableListenableFuture<SendResult<String, Object>> future = new SettableListenableFuture<>();
        when(orders.claimOutbox(OutboxKind.STOCK, 1L, 0, true, 45)).thenReturn(true);
        when(producer.sendStockSettlement(any())).thenReturn(future);
        outbox.afterOrderCommit(stock(1L));
        verify(producer, timeout(2000)).sendStockSettlement(any());
        outbox.afterOrderCommit(stock(2L));
        verify(orders, never()).claimOutbox(eq(OutboxKind.STOCK), eq(2L), anyInt(), anyBoolean(), anyInt());
        verify(orders, never()).finishOutbox(any(), anyLong(), anyInt(), anyBoolean(), anyInt());
        future.set(null);
        verify(orders, timeout(2000)).finishOutbox(OutboxKind.STOCK, 1L, 1, true, 600);
    }

    @Test
    public void compensationUsesScannedGenerationWithoutPerOrderRead() {
        DrawOrderVO candidate = order(0, 0, 0);
        candidate.setStockAttempt(2);
        when(orders.scanOutbox(OutboxKind.STOCK, 1)).thenReturn(Collections.singletonList(candidate));
        when(orders.claimOutbox(OutboxKind.STOCK, 1L, 2, false, 45)).thenReturn(true);
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
        when(orders.claimOutbox(OutboxKind.FULFILLMENT, 1L, 0, false, 45)).thenReturn(true);
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
        IDrawConfigRepository config = mock(IDrawConfigRepository.class);
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
        assertThrows(IllegalStateException.class, () -> stockListener(mock(IDrawConfigRepository.class))
                .onMessage(stockRecord(), ack, "lottery_strategy_award_stock"));
        verify(ack, never()).acknowledge();
    }

    @Test
    public void blockedStockCannotBeRetriedByBrokerDuplicate() {
        when(orders.findOrder("mq-test", 1L)).thenReturn(order(4, 0, 0));
        IDrawConfigRepository config = mock(IDrawConfigRepository.class);
        stockListener(config).onMessage(stockRecord(), ack, "lottery_strategy_award_stock");
        verify(config, never()).settleAwardStock(any());
        verify(ack).acknowledge();
    }

    private AwardFulfillmentListener invoiceListener(AwardFulfillmentHandler handler) {
        AwardFulfillmentListener listener = new AwardFulfillmentListener();
        ReflectionTestUtils.setField(listener, "orderRepository", orders);
        ReflectionTestUtils.setField(listener, "fulfillmentHandlers", Collections.singletonList(handler));
        listener.initRegistry();
        return listener;
    }

    private StockSettlementListener stockListener(IDrawConfigRepository config) {
        StockSettlementListener listener = new StockSettlementListener();
        ReflectionTestUtils.setField(listener, "orderRepository", orders);
        ReflectionTestUtils.setField(listener, "drawConfigRepository", config);
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
