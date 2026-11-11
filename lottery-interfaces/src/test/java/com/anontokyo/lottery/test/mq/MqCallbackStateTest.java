package com.anontokyo.lottery.test.mq;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.application.mq.consumer.AwardFulfillmentListener;
import com.anontokyo.lottery.application.mq.consumer.StockSettlementListener;
import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
import com.anontokyo.lottery.application.worker.LotteryXxlJob;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.service.fulfillment.AwardFulfillmentHandler;
import com.anontokyo.lottery.domain.award.service.fulfillment.AwardFulfillmentResult;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
import com.anontokyo.lottery.domain.draw.repository.IDrawConfigRepository;
import com.anontokyo.lottery.domain.support.lock.IAwardFulfillmentGuard;
import com.anontokyo.lottery.domain.support.lock.IStockSettlementGuard;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.After;
import org.junit.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 应用层接线测试：只验证回调、消费收口与 ack 顺序，不能代替数据库 CAS 验证。 */
public class MqCallbackStateTest {
    private final AwardFulfillmentEvent invoice = invoice();
    private final IOrderRepository repository = mock(IOrderRepository.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);

    @After
    public void clearJobContext() {
        // XXL-Job 使用线程上下文，测试结束必须清理。
    }

    @Test
    public void legacyKafkaFieldsRoundTripWithNewJavaNames() {
        // 旧 Broker 中的原字段可读，新生产者继续写相同字段。
        AwardStockSettlementVO stock = JSON.parseObject(
                "{\"orderId\":7,\"strategyId\":42}", AwardStockSettlementVO.class);
        assertEquals(Long.valueOf(42L), stock.getDrawConfigId());
        assertTrue(JSON.parseObject(JSON.toJSONString(stock))
                .containsKey("strategyId"));
        AwardFulfillmentEvent award = JSON.parseObject(
                "{\"orderId\":7,\"grantDate\":1893456000000}", AwardFulfillmentEvent.class);
        assertEquals(1893456000000L, award.getScheduledAt().getTime());
        assertTrue(JSON.parseObject(JSON.toJSONString(award))
                .containsKey("grantDate"));
    }

    @Test
    public void compensationSuccessMeansSent() {
        IAwardOrder order = mock(IAwardOrder.class);
        KafkaProducer producer = mock(KafkaProducer.class);
        SettableListenableFuture<SendResult<String, Object>> future = new SettableListenableFuture<>();
        when(order.scanFulfillmentEventStatus(100)).thenReturn(Collections.singletonList(invoice));
        when(producer.sendAwardFulfillment(invoice)).thenReturn(future);
        LotteryXxlJob job = new LotteryXxlJob();
        ReflectionTestUtils.setField(job, "awardOrder", order);
        ReflectionTestUtils.setField(job, "kafkaProducer", producer);
        ReflectionTestUtils.setField(job, "invoiceMqEnabled", true);
        job.lotteryOrderMQStateJobHandler();
        // 发送成功只证明到达 Kafka，不能直接标记消费完成。
        future.set(null);
        verify(order).updateFulfillmentEventStatus("mq-test", 1L, Constants.FulfillmentEventStatus.SENT.getCode());
        verify(order, never()).updateFulfillmentEventStatus(anyString(), anyLong(), eq(Constants.FulfillmentEventStatus.COMPLETE.getCode()));
    }

    @Test
    public void stockOutboxDispatchUsesScanWithoutPerOrderRead() {
        IAwardOrder order = mock(IAwardOrder.class);
        KafkaProducer producer = mock(KafkaProducer.class);
        IStockSettlementGuard guard = mock(IStockSettlementGuard.class);
        AwardStockSettlementVO candidate = new AwardStockSettlementVO();
        candidate.setuId("mq-test"); candidate.setOrderId(1L);
        SettableListenableFuture<SendResult<String, Object>> future = new SettableListenableFuture<>();
        when(order.scanStockSettlementEventStatus(200)).thenReturn(Collections.singletonList(candidate));
        when(guard.tryClaimDispatch(1L)).thenReturn(true);
        when(producer.sendStockSettlement(candidate)).thenReturn(future);
        LotteryXxlJob job = new LotteryXxlJob();
        ReflectionTestUtils.setField(job, "awardOrder", order);
        ReflectionTestUtils.setField(job, "kafkaProducer", producer);
        ReflectionTestUtils.setField(job, "stockSettlementGuard", guard);
        ReflectionTestUtils.setField(job, "stockSettlementDispatchBatchSize", 200);
        job.awardStockSettlementOutboxJobHandler();
        // 扫描已过滤 COMPLETE；逐条预读不能防止消费在读后完成，状态保护仍由回调 CAS 承担。
        verify(order, never()).isStockSettlementComplete(anyString(), anyLong());
        verify(guard).tryClaimDispatch(1L);
        verify(producer).sendStockSettlement(candidate);
        // Future 完成前不得宣布已发送；成功通知仍只推进 SENT，并释放原有 marker。
        verify(order, never()).updateStockSettlementEventStatus(anyString(), anyLong(), anyInt());
        future.set(null);
        verify(order).updateStockSettlementEventStatus("mq-test", 1L, Constants.StockSettlementEventStatus.SENT.getCode());
        verify(guard).clearDispatchMarker(1L);
    }

    @Test
    public void invoiceConsumerClosesMqBeforeAck() {
        IAwardFulfillmentGuard guard = mock(IAwardFulfillmentGuard.class);
        AwardFulfillmentHandler handler = mock(AwardFulfillmentHandler.class);
        when(guard.tryClaimProcessing(1L)).thenReturn(true);
        when(handler.supportType()).thenReturn(3);
        // 消费者反序列化成新的 VO，按字段匹配调用。
        when(handler.fulfill(any(AwardFulfillmentEvent.class))).thenReturn(new AwardFulfillmentResult("mq-test", Constants.AwardState.SUCCESS, "成功", true));
        AwardFulfillmentListener listener = listener(guard, handler);
        listener.onMessage(record(), ack, "lottery_invoice");
        org.mockito.InOrder sequence = inOrder(repository, ack);
        sequence.verify(repository).updateFulfillmentEventStatus("mq-test", 1L, Constants.FulfillmentEventStatus.COMPLETE.getCode());
        sequence.verify(ack).acknowledge();
    }

    @Test
    public void duplicateGrantedInvoiceRepairsMqAndAcknowledges() {
        when(repository.isAwardCompleted("mq-test", 1L)).thenReturn(true);
        IAwardFulfillmentGuard guard = mock(IAwardFulfillmentGuard.class);
        AwardFulfillmentListener listener = listener(guard, mock(AwardFulfillmentHandler.class));
        listener.onMessage(record(), ack, "lottery_invoice");
        verify(repository).updateFulfillmentEventStatus("mq-test", 1L, Constants.FulfillmentEventStatus.COMPLETE.getCode());
        verify(guard, never()).tryClaimProcessing(anyLong());
        verify(ack).acknowledge();
    }

    @Test
    public void mqCloseSqlErrorMustPreventAck() {
        when(repository.isAwardCompleted("mq-test", 1L)).thenReturn(true);
        doThrow(new IllegalStateException("数据库不可用")).when(repository).updateFulfillmentEventStatus(anyString(), anyLong(), anyInt());
        AwardFulfillmentListener listener = listener(mock(IAwardFulfillmentGuard.class), mock(AwardFulfillmentHandler.class));
        assertThrows(IllegalStateException.class, () -> listener.onMessage(record(), ack, "lottery_invoice"));
        verify(ack, never()).acknowledge();
    }

    @Test
    public void otherConsumerCompletedInvoiceAlsoClosesMqBeforeAck() {
        // 第一次查询未完成，但拿锁失败后查到另一个消费者已完成。
        when(repository.isAwardCompleted("mq-test", 1L)).thenReturn(false, true);
        AwardFulfillmentListener listener = listener(mock(IAwardFulfillmentGuard.class), mock(AwardFulfillmentHandler.class));
        listener.onMessage(record(), ack, "lottery_invoice");
        verify(repository).updateFulfillmentEventStatus("mq-test", 1L, Constants.FulfillmentEventStatus.COMPLETE.getCode());
        verify(ack).acknowledge();
    }

    @Test
    public void stockGapPersistsReconciliationBeforeAcknowledgment() {
        IAwardOrder order = mock(IAwardOrder.class);
        IDrawConfigRepository drawConfig = mock(IDrawConfigRepository.class);
        when(order.queryFulfillmentEventByOrderId("mq-test", 1L)).thenReturn(invoice);
        when(drawConfig.settleAwardStock(any())).thenReturn(false);
        StockSettlementListener listener = new StockSettlementListener();
        ReflectionTestUtils.setField(listener, "awardOrder", order);
        ReflectionTestUtils.setField(listener, "drawConfigRepository", drawConfig);
        AwardStockSettlementVO stock = new AwardStockSettlementVO();
        stock.setuId("mq-test");
        stock.setOrderId(1L);
        // 库存消费失败必须使用独立入口，不能冒充发送失败。
        listener.onMessage(new ConsumerRecord<>("lottery_strategy_award_stock", 0, 0, "1", JSON.toJSONString(stock)),
                ack, "lottery_strategy_award_stock");
        org.mockito.InOrder sequence = inOrder(order, ack);
        sequence.verify(order).markStockSettlementFailed("mq-test", 1L);
        sequence.verify(ack).acknowledge();
        verify(order, never()).updateStockSettlementEventStatus(anyString(), anyLong(), eq(Constants.StockSettlementEventStatus.FAIL.getCode()));
    }

    @Test
    public void stockGapPersistenceErrorMustPreventAck() {
        IAwardOrder order = mock(IAwardOrder.class);
        IDrawConfigRepository drawConfig = mock(IDrawConfigRepository.class);
        when(order.queryFulfillmentEventByOrderId("mq-test", 1L)).thenReturn(invoice);
        when(drawConfig.settleAwardStock(any())).thenReturn(false);
        doThrow(new IllegalStateException("数据库不可用")).when(order).markStockSettlementFailed("mq-test", 1L);
        StockSettlementListener listener = new StockSettlementListener();
        ReflectionTestUtils.setField(listener, "awardOrder", order);
        ReflectionTestUtils.setField(listener, "drawConfigRepository", drawConfig);
        AwardStockSettlementVO stock = new AwardStockSettlementVO();
        stock.setuId("mq-test");
        stock.setOrderId(1L);
        // 没有持久保存待对账事实时，消费异常必须暴露，不能 ACK 丢掉应完成的结算。
        assertThrows(IllegalStateException.class, () -> listener.onMessage(
                new ConsumerRecord<>("lottery_strategy_award_stock", 0, 0, "1", JSON.toJSONString(stock)),
                ack, "lottery_strategy_award_stock"));
        verify(ack, never()).acknowledge();
    }

    @Test
    public void stockCompletionInvoiceSendUsesSentAndFailCallbacks() {
        IAwardOrder order = mock(IAwardOrder.class);
        IDrawConfigRepository drawConfig = mock(IDrawConfigRepository.class);
        KafkaProducer producer = mock(KafkaProducer.class);
        SettableListenableFuture<SendResult<String, Object>> success = new SettableListenableFuture<>();
        SettableListenableFuture<SendResult<String, Object>> failure = new SettableListenableFuture<>();
        when(order.queryFulfillmentEventByOrderId("mq-test", 1L)).thenReturn(invoice);
        when(drawConfig.settleAwardStock(any())).thenReturn(true);
        when(producer.sendAwardFulfillment(invoice)).thenReturn(success, failure);
        StockSettlementListener listener = new StockSettlementListener();
        ReflectionTestUtils.setField(listener, "awardOrder", order);
        ReflectionTestUtils.setField(listener, "drawConfigRepository", drawConfig);
        ReflectionTestUtils.setField(listener, "kafkaProducer", producer);
        ReflectionTestUtils.setField(listener, "invoiceMqEnabled", true);
        AwardStockSettlementVO stock = new AwardStockSettlementVO();
        stock.setuId("mq-test");
        stock.setOrderId(1L);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("lottery_strategy_award_stock", 0, 0, "1", JSON.toJSONString(stock));
        // 不同结算重放可能都尝试投递发奖，两个发送入口必须共享相同状态语义。
        listener.onMessage(record, ack, "lottery_strategy_award_stock");
        listener.onMessage(record, ack, "lottery_strategy_award_stock");
        success.set(null);
        failure.setException(new IllegalStateException("发送失败"));
        verify(order).updateFulfillmentEventStatus("mq-test", 1L, Constants.FulfillmentEventStatus.SENT.getCode());
        verify(order).updateFulfillmentEventStatus("mq-test", 1L, Constants.FulfillmentEventStatus.FAIL.getCode());
    }

    private AwardFulfillmentListener listener(IAwardFulfillmentGuard guard, AwardFulfillmentHandler handler) {
        AwardFulfillmentListener listener = new AwardFulfillmentListener();
        ReflectionTestUtils.setField(listener, "orderRepository", repository);
        ReflectionTestUtils.setField(listener, "awardFulfillmentGuard", guard);
        ReflectionTestUtils.setField(listener, "fulfillmentHandlers", Collections.singletonList(handler));
        listener.initRegistry();
        return listener;
    }

    private ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>("lottery_invoice", 0, 0, "1", JSON.toJSONString(invoice));
    }

    private static AwardFulfillmentEvent invoice() {
        AwardFulfillmentEvent invoice = new AwardFulfillmentEvent();
        invoice.setuId("mq-test");
        invoice.setOrderId(1L);
        invoice.setAwardId("C");
        invoice.setAwardType(3);
        return invoice;
    }
}
