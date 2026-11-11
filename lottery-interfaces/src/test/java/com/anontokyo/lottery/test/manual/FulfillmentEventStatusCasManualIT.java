package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.infrastructure.dao.IDrawOrderDao;
import com.anontokyo.lottery.infrastructure.repository.OrderRepository;
import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.application.mq.consumer.AwardFulfillmentListener;
import com.anontokyo.lottery.application.mq.consumer.StockSettlementListener;
import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
import com.anontokyo.lottery.application.worker.LotteryXxlJob;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;
import com.anontokyo.lottery.domain.award.service.fulfillment.impl.CouponFulfillmentHandler;
import com.anontokyo.lottery.domain.award.service.order.impl.AwardOrderImpl;
import com.anontokyo.lottery.domain.draw.repository.IDrawConfigRepository;
import com.anontokyo.lottery.domain.support.lock.IAwardFulfillmentGuard;
import com.anontokyo.lottery.domain.support.lock.IStockSettlementGuard;
import com.anontokyo.lottery.infrastructure.po.DrawOrder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 隔离 MySQL 测试：生产 Mapper 与 Repository 的真实 SQL，不使用数据库 Mock。
 * 不装配分片插件，表放在专用测试库；不证明八分表路由或 Kafka/Redis 端到端正确性。
 */
public class FulfillmentEventStatusCasManualIT {
    private static JdbcTemplate jdbc;
    private static IDrawOrderDao dao;
    private static OrderRepository repository;

    @BeforeClass
    public static void database() throws Exception {
        String url = System.getProperty("lottery.it.mysql");
        assertNotNull("请传入专用空 MySQL URL（末尾 /，不含数据库）", url);
        // 测试仅建立专用 schema，不读取或修改现有 Lottery 库。
        try (Connection connection = DriverManager.getConnection(url + "?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE lottery_mq_cas_it");
        }
        DriverManagerDataSource source = new DriverManagerDataSource(
                url + "lottery_mq_cas_it?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
        jdbc = new JdbcTemplate(source);
        // 列与生产订单字段对应，保留 InnoDB 行锁及实际 MySQL 时间函数。
        jdbc.execute("CREATE TABLE draw_order (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "u_id VARCHAR(32), order_id BIGINT, take_id BIGINT, activity_id BIGINT, draw_config_id BIGINT ,"
                + "grant_type INT, scheduled_at DATETIME, fulfilled_at DATETIME, award_fulfillment_status INT, award_id VARCHAR(64), award_type INT,"
                + "award_name VARCHAR(64), award_content VARCHAR(128),"
                + "fulfillment_event_status INT, stock_settlement_event_status INT, create_time DATETIME, update_time DATETIME) ENGINE=InnoDB");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        // 执行生产 XML，不复制状态更新 SQL 到测试里。
        factory.setMapperLocations(new ClassPathResource("mybatis/mapper/DrawOrder_Mapper.xml"));
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        dao = new SqlSessionTemplate(factory.getObject()).getMapper(IDrawOrderDao.class);
        repository = new OrderRepository();
        ReflectionTestUtils.setField(repository, "drawOrderDao", dao);
    }

    @AfterClass
    public static void cleanupDatabase() {
        // 专用库由本测试建立，测试完成后删除，保证下一次能重新创建。
        if (jdbc != null) {
            jdbc.execute("DROP DATABASE lottery_mq_cas_it");
        }
    }

    @After
    public void clearContext() {
    }

    @Before
    public void order() {
        // 清理的是本测试 schema 的临时表，避免用例共享状态。
        jdbc.update("DELETE FROM draw_order");
        jdbc.update("INSERT INTO draw_order (u_id,order_id,draw_config_id,award_id,award_type,award_fulfillment_status,"
                + "stock_settlement_event_status,fulfillment_event_status,create_time,update_time) VALUES ('mq-test',1,10001,'C',3,0,0,0,NOW(),NOW())");
    }

    @Test
    public void completedStockCannotBeOverwrittenByLateCallbacks() {
        // 消费先完成，再收到发送成功和失败通知。
        repository.updateStockSettlementEventStatus("mq-test", 1L, Constants.StockSettlementEventStatus.COMPLETE.getCode());
        repository.updateStockSettlementEventStatus("mq-test", 1L, Constants.StockSettlementEventStatus.SENT.getCode());
        assertEquals(1, stockState());
        repository.updateStockSettlementEventStatus("mq-test", 1L, Constants.StockSettlementEventStatus.FAIL.getCode());
        assertEquals(1, stockState());
    }

    @Test
    public void stockStateMatrixAllowsRetriesAndProtectsSentAndComplete() {
        // 库存加入 BLOCKED=4；通用发送入口不能写入或解除待对账状态。
        int[][] expected = {{0, 1, 2, 3, 0}, {1, 1, 1, 1, 1}, {2, 1, 2, 3, 2}, {3, 1, 3, 3, 3}, {4, 1, 4, 4, 4}};
        for (int from = 0; from < 5; from++) {
            for (int target = 0; target < 5; target++) {
                jdbc.update("UPDATE draw_order SET stock_settlement_event_status=?", from);
                repository.updateStockSettlementEventStatus("mq-test", 1L, target);
                assertEquals("库存转换 " + from + "→" + target, expected[from][target], stockState());
            }
        }
    }

    @Test
    public void invoiceStateMatrixRequiresGrantFactForCompletion() {
        int[][] expected = {{0, 1, 2, 3}, {1, 1, 1, 1}, {2, 1, 2, 3}, {3, 1, 3, 3}};
        for (int from = 0; from < 4; from++) {
            for (int target = 0; target < 4; target++) {
                jdbc.update("UPDATE draw_order SET fulfillment_event_status=?,award_fulfillment_status=1", from);
                repository.updateFulfillmentEventStatus("mq-test", 1L, target);
                assertEquals("发奖转换 " + from + "→" + target, expected[from][target], invoiceState());
            }
            jdbc.update("UPDATE draw_order SET fulfillment_event_status=?,award_fulfillment_status=0", from);
            repository.updateFulfillmentEventStatus("mq-test", 1L, 1);
            assertEquals("未发奖不能标记消费完成", from, invoiceState());
        }
    }

    @Test
    public void stockGapBlocksCallbacksButSuccessfulLedgerCanComplete() {
        for (int from = 0; from < 5; from++) {
            jdbc.update("UPDATE draw_order SET stock_settlement_event_status=?", from);
            repository.markStockSettlementFailed("mq-test", 1L);
            assertEquals(from == 1 ? 1 : 4, stockState());
            if (from != 1) {
                // 业务分歧不得被发送通知解除；真实结算成功仍可以收口。
                repository.updateStockSettlementEventStatus("mq-test", 1L, 3);
                assertEquals(4, stockState());
                repository.updateStockSettlementEventStatus("mq-test", 1L, 2);
                assertEquals(4, stockState());
                repository.updateStockSettlementEventStatus("mq-test", 1L, 1);
                assertEquals(1, stockState());
            }
        }
    }

    @Test
    public void duplicateAndLateEventsDoNotPostponeTimeout() {
        jdbc.update("UPDATE draw_order SET stock_settlement_event_status=3,fulfillment_event_status=3,update_time=DATE_SUB(NOW(),INTERVAL 11 MINUTE)");
        Date before = updateTime();
        DrawOrder request = request();
        request.setStockSettlementEventStatus(3);
        // 影响行数必须为零；即便驱动使用 matched rows，也不会把同状态视为一次推进。
        assertEquals(0, dao.updateStockSettlementEventStatus(request));
        request.setStockSettlementEventStatus(2);
        assertEquals(0, dao.updateStockSettlementEventStatus(request));
        request.setFulfillmentEventStatus(3);
        assertEquals(0, dao.updateFulfillmentEventStatus(request));
        request.setFulfillmentEventStatus(2);
        assertEquals(0, dao.updateFulfillmentEventStatus(request));
        assertEquals(before, updateTime());
        assertEquals(1, repository.scanStockSettlementEventStatus(10).size());
        jdbc.update("UPDATE draw_order SET stock_settlement_event_status=1");
        assertEquals(1, repository.scanFulfillmentEventStatus(10).size());
    }

    @Test
    public void realScanSqlRecoversPendingStatesAndExcludesCompletedAwards() {
        // 库存 INIT、FAIL 立即可扫；SENT 仅超过 10 分钟，COMPLETE 永不重投。
        for (int state : Arrays.asList(0, 2, 3, 1)) {
            jdbc.update("UPDATE draw_order SET stock_settlement_event_status=?,update_time=NOW()", state);
            assertEquals(state == 0 || state == 2 ? 1 : 0, repository.scanStockSettlementEventStatus(10).size());
            jdbc.update("UPDATE draw_order SET update_time=DATE_SUB(NOW(),INTERVAL 11 MINUTE)");
            assertEquals(state == 1 ? 0 : 1, repository.scanStockSettlementEventStatus(10).size());
        }
        // 发奖 INIT 保留 30 分钟窗口；旧补偿误写 1 的未发奖行也要恢复。
        for (int state = 0; state < 4; state++) {
            jdbc.update("UPDATE draw_order SET stock_settlement_event_status=1,award_fulfillment_status=0,fulfillment_event_status=?,create_time=NOW(),update_time=NOW()", state);
            assertEquals(state == 2 ? 1 : 0, repository.scanFulfillmentEventStatus(10).size());
            jdbc.update("UPDATE draw_order SET create_time=DATE_SUB(NOW(),INTERVAL 31 MINUTE),update_time=DATE_SUB(NOW(),INTERVAL 11 MINUTE)");
            assertEquals(1, repository.scanFulfillmentEventStatus(10).size());
            jdbc.update("UPDATE draw_order SET award_fulfillment_status=1");
            assertEquals(0, repository.scanFulfillmentEventStatus(10).size());
            jdbc.update("UPDATE draw_order SET award_fulfillment_status=0,stock_settlement_event_status=0");
            assertEquals(0, repository.scanFulfillmentEventStatus(10).size());
        }
    }

    @Test
    public void stockConsumerCompletesBeforeRealProducerCallbacks() {
        KafkaProducer producer = mock(KafkaProducer.class);
        SettableListenableFuture<SendResult<String, Object>> first = new SettableListenableFuture<>();
        SettableListenableFuture<SendResult<String, Object>> second = new SettableListenableFuture<>();
        when(producer.sendStockSettlement(any())).thenReturn(first, second);
        LotteryXxlJob job = job(producer);
        // 两次发送均挂起：可控 Future 模拟 Redis 标记过期后的重试，不使用真实 Kafka/Redis。
        job.awardStockSettlementOutboxJobHandler();
        job.awardStockSettlementOutboxJobHandler();
        consumeStock(producer, true);
        assertEquals(1, stockState());
        first.set(null);
        assertEquals(1, stockState());
        second.setException(new IllegalStateException("迟到的发送失败"));
        assertEquals(1, stockState());
        // 真实消费者重复消息短路，不会再次结算。
        consumeStock(producer, false);
        assertEquals(1, stockState());
    }

    @Test
    public void retryCallbacksOutOfOrderKeepSendSuccessUntilConsumption() {
        KafkaProducer producer = mock(KafkaProducer.class);
        List<SettableListenableFuture<SendResult<String, Object>>> futures = new ArrayList<>();
        when(producer.sendStockSettlement(any())).thenAnswer(invocation -> {
            SettableListenableFuture<SendResult<String, Object>> future = new SettableListenableFuture<>();
            futures.add(future);
            return future;
        });
        LotteryXxlJob job = job(producer);
        // 第一次明确失败落 FAIL，补偿必须扫描并重试。
        job.awardStockSettlementOutboxJobHandler();
        futures.get(0).setException(new IllegalStateException("首次失败"));
        assertEquals(2, stockState());
        for (int i = 0; i < 3; i++) {
            job.awardStockSettlementOutboxJobHandler();
        }
        assertEquals(4, futures.size());
        // 新重试先成功，旧失败后到，另一次旧成功更晚到。
        futures.get(3).set(null);
        assertEquals(3, stockState());
        futures.get(1).setException(new IllegalStateException("旧重试迟到失败"));
        assertEquals(3, stockState());
        futures.get(2).set(null);
        assertEquals(3, stockState());
        consumeStock(producer, true);
        assertEquals(1, stockState());
        assertTrue(repository.scanStockSettlementEventStatus(10).isEmpty());
    }

    @Test
    public void timedOutStockCanBeResentEvenIfRetrySendFails() {
        jdbc.update("UPDATE draw_order SET stock_settlement_event_status=3,update_time=DATE_SUB(NOW(),INTERVAL 11 MINUTE)");
        KafkaProducer producer = mock(KafkaProducer.class);
        SettableListenableFuture<SendResult<String, Object>> failed = new SettableListenableFuture<>();
        SettableListenableFuture<SendResult<String, Object>> succeeded = new SettableListenableFuture<>();
        when(producer.sendStockSettlement(any())).thenReturn(failed, succeeded);
        LotteryXxlJob job = job(producer);
        job.awardStockSettlementOutboxJobHandler();
        failed.setException(new IllegalStateException("超时重投失败"));
        assertEquals(3, stockState());
        // 失败回调未刷新补偿时间，下一轮仍然能扫到，而不会永久挂起。
        job.awardStockSettlementOutboxJobHandler();
        verify(producer, times(2)).sendStockSettlement(any());
        succeeded.set(null);
        consumeStock(producer, true);
        assertEquals(1, stockState());
    }

    @Test
    public void invoiceConsumerAndLateCallbacksShareRealCas() {
        jdbc.update("UPDATE draw_order SET stock_settlement_event_status=1,fulfillment_event_status=2");
        KafkaProducer producer = mock(KafkaProducer.class);
        SettableListenableFuture<SendResult<String, Object>> success = new SettableListenableFuture<>();
        SettableListenableFuture<SendResult<String, Object>> failure = new SettableListenableFuture<>();
        when(producer.sendAwardFulfillment(any())).thenReturn(success, failure);
        LotteryXxlJob job = job(producer);
        job.lotteryOrderMQStateJobHandler();
        job.lotteryOrderMQStateJobHandler();
        consumeInvoice();
        assertEquals(1, invoiceState());
        assertTrue(repository.isAwardCompleted("mq-test", 1L));
        success.set(null);
        failure.setException(new IllegalStateException("迟到的发奖发送失败"));
        assertEquals(1, invoiceState());
        consumeInvoice();
        assertTrue(repository.scanFulfillmentEventStatus(10).isEmpty());
    }

    @Test
    public void invoiceRetriesAlsoIgnoreOldFailuresAfterNewSuccess() {
        jdbc.update("UPDATE draw_order SET stock_settlement_event_status=1,fulfillment_event_status=2");
        KafkaProducer producer = mock(KafkaProducer.class);
        List<SettableListenableFuture<SendResult<String, Object>>> futures = new ArrayList<>();
        when(producer.sendAwardFulfillment(any())).thenAnswer(invocation -> {
            SettableListenableFuture<SendResult<String, Object>> future = new SettableListenableFuture<>();
            futures.add(future);
            return future;
        });
        LotteryXxlJob job = job(producer);
        for (int i = 0; i < 3; i++) {
            job.lotteryOrderMQStateJobHandler();
        }
        // 新重试的成功先到，旧重试的失败和成功都不能覆盖或刷新它。
        futures.get(2).set(null);
        assertEquals(3, invoiceState());
        Date sentAt = updateTime();
        futures.get(0).setException(new IllegalStateException("旧发奖重试迟到失败"));
        futures.get(1).set(null);
        assertEquals(3, invoiceState());
        assertEquals(sentAt, updateTime());
        consumeInvoice();
        assertEquals(1, invoiceState());
    }

    @Test
    public void legacyInvoiceCompleteWithoutGrantIsActuallyRecoverable() {
        // 模拟旧补偿发送成功误写 COMPLETE，但消费者未完成的真实历史形态。
        jdbc.update("UPDATE draw_order SET stock_settlement_event_status=1,fulfillment_event_status=1,award_fulfillment_status=0,update_time=DATE_SUB(NOW(),INTERVAL 11 MINUTE)");
        KafkaProducer producer = mock(KafkaProducer.class);
        SettableListenableFuture<SendResult<String, Object>> future = new SettableListenableFuture<>();
        when(producer.sendAwardFulfillment(any())).thenReturn(future);
        job(producer).lotteryOrderMQStateJobHandler();
        verify(producer).sendAwardFulfillment(any());
        future.set(null);
        consumeInvoice();
        assertTrue(repository.isAwardCompleted("mq-test", 1L));
        assertTrue(repository.scanFulfillmentEventStatus(10).isEmpty());
    }

    @Test
    public void concurrentSqlUpdatesNeverUndoConsumption() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            // 每轮真正由独立连接竞争同一行锁，Future.get 会传播工作线程异常。
            for (int round = 0; round < 20; round++) {
                jdbc.update("UPDATE draw_order SET stock_settlement_event_status=0,fulfillment_event_status=0,award_fulfillment_status=1");
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> actions = new ArrayList<>();
                for (int index = 0; index < 12; index++) {
                    final int event = index % 4;
                    actions.add(pool.submit(() -> {
                        start.await();
                        if (event == 3) {
                            repository.markStockSettlementFailed("mq-test", 1L);
                        } else {
                            repository.updateStockSettlementEventStatus("mq-test", 1L, new int[]{1, 2, 3}[event]);
                        }
                        repository.updateFulfillmentEventStatus("mq-test", 1L, new int[]{1, 2, 3, 2}[event]);
                        return null;
                    }));
                }
                start.countDown();
                // 所有事件返回之后检查终态，不吞任何 SQL 或线程失败。
                for (Future<?> action : actions) {
                    action.get(30, TimeUnit.SECONDS);
                }
                assertEquals(1, stockState());
                assertEquals(1, invoiceState());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private LotteryXxlJob job(KafkaProducer producer) {
        IStockSettlementGuard guard = mock(IStockSettlementGuard.class);
        when(guard.tryClaimDispatch(1L)).thenReturn(true);
        LotteryXxlJob job = new LotteryXxlJob();
        ReflectionTestUtils.setField(job, "awardOrder", awardOrder());
        ReflectionTestUtils.setField(job, "kafkaProducer", producer);
        ReflectionTestUtils.setField(job, "stockSettlementGuard", guard);
        ReflectionTestUtils.setField(job, "stockSettlementDispatchBatchSize", 200);
        ReflectionTestUtils.setField(job, "invoiceMqEnabled", true);
        return job;
    }

    private AwardOrderImpl awardOrder() {
        AwardOrderImpl order = new AwardOrderImpl();
        ReflectionTestUtils.setField(order, "orderRepository", repository);
        return order;
    }

    private void consumeStock(KafkaProducer producer, boolean firstConsumption) {
        IDrawConfigRepository drawConfig = mock(IDrawConfigRepository.class);
        when(drawConfig.settleAwardStock(any())).thenReturn(true);
        StockSettlementListener listener = new StockSettlementListener();
        ReflectionTestUtils.setField(listener, "awardOrder", awardOrder());
        ReflectionTestUtils.setField(listener, "drawConfigRepository", drawConfig);
        ReflectionTestUtils.setField(listener, "kafkaProducer", producer);
        // 库存用例关闭后续发奖，避免混入第二条消息链；发奖链另有完整用例。
        ReflectionTestUtils.setField(listener, "invoiceMqEnabled", false);
        AwardStockSettlementVO stock = new AwardStockSettlementVO();
        stock.setuId("mq-test");
        stock.setOrderId(1L);
        stock.setDrawConfigId(10001L);
        stock.setAwardId("C");
        Acknowledgment ack = mock(Acknowledgment.class);
        listener.onMessage(new ConsumerRecord<>("lottery_strategy_award_stock", 0, 0, "1", JSON.toJSONString(stock)),
                ack, "lottery_strategy_award_stock");
        verify(ack).acknowledge();
        verify(drawConfig, times(firstConsumption ? 1 : 0)).settleAwardStock(any());
    }

    private void consumeInvoice() {
        IAwardFulfillmentGuard guard = mock(IAwardFulfillmentGuard.class);
        when(guard.tryClaimProcessing(1L)).thenReturn(true);
        CouponFulfillmentHandler handler = new CouponFulfillmentHandler();
        ReflectionTestUtils.setField(handler, "orderRepository", repository);
        AwardFulfillmentListener listener = new AwardFulfillmentListener();
        ReflectionTestUtils.setField(listener, "orderRepository", repository);
        ReflectionTestUtils.setField(listener, "awardFulfillmentGuard", guard);
        ReflectionTestUtils.setField(listener, "fulfillmentHandlers", Collections.singletonList(handler));
        listener.initRegistry();
        AwardFulfillmentEvent invoice = repository.queryFulfillmentEventByOrderId("mq-test", 1L);
        Acknowledgment ack = mock(Acknowledgment.class);
        listener.onMessage(new ConsumerRecord<>("lottery_invoice", 0, 0, "1", JSON.toJSONString(invoice)), ack, "lottery_invoice");
        verify(ack).acknowledge();
    }

    private DrawOrder request() {
        DrawOrder request = new DrawOrder();
        request.setuId("mq-test");
        request.setOrderId(1L);
        return request;
    }

    private int invoiceState() {
        return jdbc.queryForObject("SELECT fulfillment_event_status FROM draw_order WHERE order_id=1", Integer.class);
    }

    private Date updateTime() {
        return jdbc.queryForObject("SELECT update_time FROM draw_order WHERE order_id=1", Date.class);
    }

    private int stockState() {
        return jdbc.queryForObject("SELECT stock_settlement_event_status FROM draw_order WHERE order_id=1", Integer.class);
    }
}
