package com.anontokyo.lottery.test.mq;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.application.mq.consumer.LotteryInvoiceListener;
import com.anontokyo.lottery.application.mq.consumer.LotteryStrategyAwardStockListener;
import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
import com.anontokyo.lottery.application.worker.LotteryXxlJob;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.service.fulfillment.AwardFulfillmentHandler;
import com.anontokyo.lottery.domain.award.service.fulfillment.AwardFulfillmentResult;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
import com.anontokyo.lottery.domain.strategy.repository.IStrategyRepository;
import com.anontokyo.lottery.domain.support.lock.IInvoiceMqProcessGuard;
import com.xxl.job.core.context.XxlJobContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.After;
import org.junit.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.util.Collections;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 应用层接线测试：只验证回调、消费收口与 ack 顺序，不能代替数据库 CAS 验证。 */
public class MqCallbackStateTest {
    private final InvoiceVO invoice = invoice();
    private final IOrderRepository repository = mock(IOrderRepository.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);

    @After
    public void clearJobContext() {
        // XXL-Job 使用线程上下文，测试结束必须清理。
        XxlJobContext.setXxlJobContext(null);
    }

    @Test
    public void compensationSuccessMeansSent() {
        IAwardOrder order = mock(IAwardOrder.class);
        KafkaProducer producer = mock(KafkaProducer.class);
        SettableListenableFuture<SendResult<String, Object>> future = new SettableListenableFuture<>();
        when(order.shardingDbCount()).thenReturn(1);
        when(order.shardingTableCount()).thenReturn(1);
        when(order.scanInvoiceMqState(1, 0, 100)).thenReturn(Collections.singletonList(invoice));
        when(producer.sendLotteryInvoice(invoice)).thenReturn(future);
        LotteryXxlJob job = new LotteryXxlJob();
        ReflectionTestUtils.setField(job, "awardOrder", order);
        ReflectionTestUtils.setField(job, "kafkaProducer", producer);
        ReflectionTestUtils.setField(job, "invoiceMqEnabled", true);
        XxlJobContext.setXxlJobContext(new XxlJobContext(1, "1", null, 0, 1));
        job.lotteryOrderMQStateJobHandler();
        // 发送成功只证明到达 Kafka，不能直接标记消费完成。
        future.set(null);
        verify(order).updateInvoiceMqState("mq-test", 1L, Constants.MQState.SENT.getCode());
        verify(order, never()).updateInvoiceMqState(anyString(), anyLong(), eq(Constants.MQState.COMPLETE.getCode()));
    }

    @Test
    public void invoiceConsumerClosesMqBeforeAck() {
        IInvoiceMqProcessGuard guard = mock(IInvoiceMqProcessGuard.class);
        AwardFulfillmentHandler handler = mock(AwardFulfillmentHandler.class);
        when(guard.tryClaimProcessing(1L)).thenReturn(true);
        when(handler.supportType()).thenReturn(3);
        // 消费者反序列化成新的 VO，按字段匹配调用。
        when(handler.fulfill(any(InvoiceVO.class))).thenReturn(new AwardFulfillmentResult("mq-test", Constants.AwardState.SUCCESS, "成功", true));
        LotteryInvoiceListener listener = listener(guard, handler);
        listener.onMessage(record(), ack, "lottery_invoice");
        org.mockito.InOrder sequence = inOrder(repository, ack);
        sequence.verify(repository).updateInvoiceMqState("mq-test", 1L, Constants.MQState.COMPLETE.getCode());
        sequence.verify(ack).acknowledge();
    }

    @Test
    public void duplicateGrantedInvoiceRepairsMqAndAcknowledges() {
        when(repository.isAwardCompleted("mq-test", 1L)).thenReturn(true);
        IInvoiceMqProcessGuard guard = mock(IInvoiceMqProcessGuard.class);
        LotteryInvoiceListener listener = listener(guard, mock(AwardFulfillmentHandler.class));
        listener.onMessage(record(), ack, "lottery_invoice");
        verify(repository).updateInvoiceMqState("mq-test", 1L, Constants.MQState.COMPLETE.getCode());
        verify(guard, never()).tryClaimProcessing(anyLong());
        verify(ack).acknowledge();
    }

    @Test
    public void mqCloseSqlErrorMustPreventAck() {
        when(repository.isAwardCompleted("mq-test", 1L)).thenReturn(true);
        doThrow(new IllegalStateException("数据库不可用")).when(repository).updateInvoiceMqState(anyString(), anyLong(), anyInt());
        LotteryInvoiceListener listener = listener(mock(IInvoiceMqProcessGuard.class), mock(AwardFulfillmentHandler.class));
        assertThrows(IllegalStateException.class, () -> listener.onMessage(record(), ack, "lottery_invoice"));
        verify(ack, never()).acknowledge();
    }

    @Test
    public void otherConsumerCompletedInvoiceAlsoClosesMqBeforeAck() {
        // 第一次查询未完成，但拿锁失败后查到另一个消费者已完成。
        when(repository.isAwardCompleted("mq-test", 1L)).thenReturn(false, true);
        LotteryInvoiceListener listener = listener(mock(IInvoiceMqProcessGuard.class), mock(AwardFulfillmentHandler.class));
        listener.onMessage(record(), ack, "lottery_invoice");
        verify(repository).updateInvoiceMqState("mq-test", 1L, Constants.MQState.COMPLETE.getCode());
        verify(ack).acknowledge();
    }

    @Test
    public void stockGapPersistsReconciliationBeforeAcknowledgment() {
        IAwardOrder order = mock(IAwardOrder.class);
        IStrategyRepository strategy = mock(IStrategyRepository.class);
        when(order.queryInvoiceByOrderId("mq-test", 1L)).thenReturn(invoice);
        when(strategy.settleAwardStock(any())).thenReturn(false);
        LotteryStrategyAwardStockListener listener = new LotteryStrategyAwardStockListener();
        ReflectionTestUtils.setField(listener, "awardOrder", order);
        ReflectionTestUtils.setField(listener, "strategyRepository", strategy);
        AwardStockSettlementVO stock = new AwardStockSettlementVO();
        stock.setuId("mq-test");
        stock.setOrderId(1L);
        // 库存消费失败必须使用独立入口，不能冒充发送失败。
        listener.onMessage(new ConsumerRecord<>("lottery_strategy_award_stock", 0, 0, "1", JSON.toJSONString(stock)),
                ack, "lottery_strategy_award_stock");
        org.mockito.InOrder sequence = inOrder(order, ack);
        sequence.verify(order).markStockSettlementFailed("mq-test", 1L);
        sequence.verify(ack).acknowledge();
        verify(order, never()).updateStockMqState(anyString(), anyLong(), eq(Constants.MQState.FAIL.getCode()));
    }

    @Test
    public void stockGapPersistenceErrorMustPreventAck() {
        IAwardOrder order = mock(IAwardOrder.class);
        IStrategyRepository strategy = mock(IStrategyRepository.class);
        when(order.queryInvoiceByOrderId("mq-test", 1L)).thenReturn(invoice);
        when(strategy.settleAwardStock(any())).thenReturn(false);
        doThrow(new IllegalStateException("数据库不可用")).when(order).markStockSettlementFailed("mq-test", 1L);
        LotteryStrategyAwardStockListener listener = new LotteryStrategyAwardStockListener();
        ReflectionTestUtils.setField(listener, "awardOrder", order);
        ReflectionTestUtils.setField(listener, "strategyRepository", strategy);
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
        IStrategyRepository strategy = mock(IStrategyRepository.class);
        KafkaProducer producer = mock(KafkaProducer.class);
        SettableListenableFuture<SendResult<String, Object>> success = new SettableListenableFuture<>();
        SettableListenableFuture<SendResult<String, Object>> failure = new SettableListenableFuture<>();
        when(order.queryInvoiceByOrderId("mq-test", 1L)).thenReturn(invoice);
        when(strategy.settleAwardStock(any())).thenReturn(true);
        when(producer.sendLotteryInvoice(invoice)).thenReturn(success, failure);
        LotteryStrategyAwardStockListener listener = new LotteryStrategyAwardStockListener();
        ReflectionTestUtils.setField(listener, "awardOrder", order);
        ReflectionTestUtils.setField(listener, "strategyRepository", strategy);
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
        verify(order).updateInvoiceMqState("mq-test", 1L, Constants.MQState.SENT.getCode());
        verify(order).updateInvoiceMqState("mq-test", 1L, Constants.MQState.FAIL.getCode());
    }

    private LotteryInvoiceListener listener(IInvoiceMqProcessGuard guard, AwardFulfillmentHandler handler) {
        LotteryInvoiceListener listener = new LotteryInvoiceListener();
        ReflectionTestUtils.setField(listener, "orderRepository", repository);
        ReflectionTestUtils.setField(listener, "invoiceMqProcessGuard", guard);
        ReflectionTestUtils.setField(listener, "fulfillmentHandlers", Collections.singletonList(handler));
        listener.initRegistry();
        return listener;
    }

    private ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>("lottery_invoice", 0, 0, "1", JSON.toJSONString(invoice));
    }

    private static InvoiceVO invoice() {
        InvoiceVO invoice = new InvoiceVO();
        invoice.setuId("mq-test");
        invoice.setOrderId(1L);
        invoice.setAwardId("C");
        invoice.setAwardType(3);
        return invoice;
    }
}
