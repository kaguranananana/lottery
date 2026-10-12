package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.infrastructure.dao.IUserStrategyExportDao;
import com.anontokyo.lottery.infrastructure.repository.OrderRepository;
import cn.bugstack.middleware.db.router.strategy.IDBRouterStrategy;
import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.application.mq.consumer.LotteryInvoiceListener;
import com.anontokyo.lottery.application.mq.consumer.LotteryStrategyAwardStockListener;
import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
import com.anontokyo.lottery.application.worker.LotteryXxlJob;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.award.service.fulfillment.impl.CouponFulfillmentHandler;
import com.anontokyo.lottery.domain.award.service.order.impl.AwardOrderImpl;
import com.anontokyo.lottery.domain.strategy.repository.IStrategyRepository;
import com.anontokyo.lottery.domain.support.lock.IInvoiceMqProcessGuard;
import com.anontokyo.lottery.domain.support.lock.IStrategyAwardStockMqGuard;
import com.anontokyo.lottery.infrastructure.po.UserStrategyExport;
import com.xxl.job.core.context.XxlJobContext;
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
public class MqStateCasManualIT {
    private static JdbcTemplate jdbc;
    private static IUserStrategyExportDao dao;
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
        jdbc.execute("CREATE TABLE user_strategy_export (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "u_id VARCHAR(32), order_id BIGINT, take_id BIGINT, activity_id BIGINT, strategy_id BIGINT, strategy_mode INT,"
                + "grant_type INT, grant_date DATETIME, grant_state INT, award_id VARCHAR(64), award_type INT,"
                + "award_name VARCHAR(64), award_content VARCHAR(128), uuid VARCHAR(128) UNIQUE,"
                + "mq_state INT, stock_mq_state INT, create_time DATETIME, update_time DATETIME) ENGINE=InnoDB");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        // 执行生产 XML，不复制状态更新 SQL 到测试里。
        factory.setMapperLocations(new ClassPathResource("mybatis/mapper/UserStrategyExport_Mapper.xml"));
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        dao = new SqlSessionTemplate(factory.getObject()).getMapper(IUserStrategyExportDao.class);
        repository = new OrderRepository();
        ReflectionTestUtils.setField(repository, "userStrategyExportDao", dao);
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
        XxlJobContext.setXxlJobContext(null);
    }

    @Before
    public void order() {
        // 清理的是本测试 schema 的临时表，避免用例共享状态。
        jdbc.update("DELETE FROM user_strategy_export");
        jdbc.update("INSERT INTO user_strategy_export (u_id,order_id,strategy_id,award_id,award_type,uuid,grant_state,"
                + "stock_mq_state,mq_state,create_time,update_time) VALUES ('mq-test',1,10001,'C',3,'1',0,0,0,NOW(),NOW())");
    }

    @Test
    public void completedStockCannotBeOverwrittenByLateCallbacks() {
        // 消费先完成，再收到发送成功和失败通知。
        repository.updateStockMqState("mq-test", 1L, Constants.MQState.COMPLETE.getCode());
        repository.updateStockMqState("mq-test", 1L, Constants.MQState.SENT.getCode());
        assertEquals(1, stockState());
        repository.updateStockMqState("mq-test", 1L, Constants.MQState.FAIL.getCode());
        assertEquals(1, stockState());
    }

    @Test
    public void stockStateMatrixAllowsRetriesAndProtectsSentAndComplete() {
        // 行是旧状态 0/1/2/3，列是目标状态 0/1/2/3；0 不作为更新事件。
        int[][] expected = {{0, 1, 2, 3}, {1, 1, 1, 1}, {2, 1, 2, 3}, {3, 1, 3, 3}};
        for (int from = 0; from < 4; from++) {
            for (int target = 0; target < 4; target++) {
                jdbc.update("UPDATE user_strategy_export SET stock_mq_state=?", from);
                repository.updateStockMqState("mq-test", 1L, target);
                assertEquals("库存转换 " + from + "→" + target, expected[from][target], stockState());
            }
        }
    }

    @Test
    public void invoiceStateMatrixRequiresGrantFactForCompletion() {
        int[][] expected = {{0, 1, 2, 3}, {1, 1, 1, 1}, {2, 1, 2, 3}, {3, 1, 3, 3}};
        for (int from = 0; from < 4; from++) {
            for (int target = 0; target < 4; target++) {
                jdbc.update("UPDATE user_strategy_export SET mq_state=?,grant_state=1", from);
                repository.updateInvoiceMqState("mq-test", 1L, target);
                assertEquals("发奖转换 " + from + "→" + target, expected[from][target], invoiceState());
            }
            jdbc.update("UPDATE user_strategy_export SET mq_state=?,grant_state=0", from);
            repository.updateInvoiceMqState("mq-test", 1L, 1);
            assertEquals("未发奖不能标记消费完成", from, invoiceState());
        }
    }

    @Test
    public void consumerFailureCanRetryFromSentButCannotOverwriteComplete() {
        for (int from = 0; from < 4; from++) {
            jdbc.update("UPDATE user_strategy_export SET stock_mq_state=?", from);
            repository.markStockSettlementFailed("mq-test", 1L);
            assertEquals(from == 1 ? 1 : 2, stockState());
            if (from != 1) {
                // 结算 FAIL 必须能继续进入 SENT 与 COMPLETE，不能只保护 INIT。
                repository.updateStockMqState("mq-test", 1L, 3);
                assertEquals(3, stockState());
                repository.updateStockMqState("mq-test", 1L, 1);
                assertEquals(1, stockState());
            }
        }
    }

    @Test
    public void duplicateAndLateEventsDoNotPostponeTimeout() {
        jdbc.update("UPDATE user_strategy_export SET stock_mq_state=3,mq_state=3,update_time=DATE_SUB(NOW(),INTERVAL 11 MINUTE)");
        Date before = updateTime();
        UserStrategyExport request = request();
        request.setStockMqState(3);
        // 影响行数必须为零；即便驱动使用 matched rows，也不会把同状态视为一次推进。
        assertEquals(0, dao.updateStockMqState(request));
        request.setStockMqState(2);
        assertEquals(0, dao.updateStockMqState(request));
        request.setMqState(3);
        assertEquals(0, dao.updateInvoiceMqState(request));
        request.setMqState(2);
        assertEquals(0, dao.updateInvoiceMqState(request));
        assertEquals(before, updateTime());
        assertEquals(1, repository.scanAwardStockMqState(10).size());
        jdbc.update("UPDATE user_strategy_export SET stock_mq_state=1");
        assertEquals(1, repository.scanInvoiceMqState(10).size());
    }

    @Test
    public void realScanSqlRecoversPendingStatesAndExcludesCompletedAwards() {
        // 库存 INIT、FAIL 立即可扫；SENT 仅超过 10 分钟，COMPLETE 永不重投。
        for (int state : Arrays.asList(0, 2, 3, 1)) {
            jdbc.update("UPDATE user_strategy_export SET stock_mq_state=?,update_time=NOW()", state);
            assertEquals(state == 0 || state == 2 ? 1 : 0, repository.scanAwardStockMqState(10).size());
            jdbc.update("UPDATE user_strategy_export SET update_time=DATE_SUB(NOW(),INTERVAL 11 MINUTE)");
            assertEquals(state == 1 ? 0 : 1, repository.scanAwardStockMqState(10).size());
        }
        // 发奖 INIT 保留 30 分钟窗口；旧补偿误写 1 的未发奖行也要恢复。
        for (int state = 0; state < 4; state++) {
            jdbc.update("UPDATE user_strategy_export SET stock_mq_state=1,grant_state=0,mq_state=?,create_time=NOW(),update_time=NOW()", state);
            assertEquals(state == 2 ? 1 : 0, repository.scanInvoiceMqState(10).size());
            jdbc.update("UPDATE user_strategy_export SET create_time=DATE_SUB(NOW(),INTERVAL 31 MINUTE),update_time=DATE_SUB(NOW(),INTERVAL 11 MINUTE)");
            assertEquals(1, repository.scanInvoiceMqState(10).size());
            jdbc.update("UPDATE user_strategy_export SET grant_state=1");
            assertEquals(0, repository.scanInvoiceMqState(10).size());
            jdbc.update("UPDATE user_strategy_export SET grant_state=0,stock_mq_state=0");
            assertEquals(0, repository.scanInvoiceMqState(10).size());
        }
    }

    @Test
    public void stockConsumerCompletesBeforeRealProducerCallbacks() {
        KafkaProducer producer = mock(KafkaProducer.class);
        SettableListenableFuture<SendResult<String, Object>> first = new SettableListenableFuture<>();
        SettableListenableFuture<SendResult<String, Object>> second = new SettableListenableFuture<>();
        when(producer.sendStrategyAwardStock(any())).thenReturn(first, second);
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
        when(producer.sendStrategyAwardStock(any())).thenAnswer(invocation -> {
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
        assertTrue(repository.scanAwardStockMqState(10).isEmpty());
    }

    @Test
    public void timedOutStockCanBeResentEvenIfRetrySendFails() {
        jdbc.update("UPDATE user_strategy_export SET stock_mq_state=3,update_time=DATE_SUB(NOW(),INTERVAL 11 MINUTE)");
        KafkaProducer producer = mock(KafkaProducer.class);
        SettableListenableFuture<SendResult<String, Object>> failed = new SettableListenableFuture<>();
        SettableListenableFuture<SendResult<String, Object>> succeeded = new SettableListenableFuture<>();
        when(producer.sendStrategyAwardStock(any())).thenReturn(failed, succeeded);
        LotteryXxlJob job = job(producer);
        job.awardStockSettlementOutboxJobHandler();
        failed.setException(new IllegalStateException("超时重投失败"));
        assertEquals(3, stockState());
        // 失败回调未刷新补偿时间，下一轮仍然能扫到，而不会永久挂起。
        job.awardStockSettlementOutboxJobHandler();
        verify(producer, times(2)).sendStrategyAwardStock(any());
        succeeded.set(null);
        consumeStock(producer, true);
        assertEquals(1, stockState());
    }

    @Test
    public void invoiceConsumerAndLateCallbacksShareRealCas() {
        jdbc.update("UPDATE user_strategy_export SET stock_mq_state=1,mq_state=2");
        KafkaProducer producer = mock(KafkaProducer.class);
        SettableListenableFuture<SendResult<String, Object>> success = new SettableListenableFuture<>();
        SettableListenableFuture<SendResult<String, Object>> failure = new SettableListenableFuture<>();
        when(producer.sendLotteryInvoice(any())).thenReturn(success, failure);
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
        assertTrue(repository.scanInvoiceMqState(10).isEmpty());
    }

    @Test
    public void invoiceRetriesAlsoIgnoreOldFailuresAfterNewSuccess() {
        jdbc.update("UPDATE user_strategy_export SET stock_mq_state=1,mq_state=2");
        KafkaProducer producer = mock(KafkaProducer.class);
        List<SettableListenableFuture<SendResult<String, Object>>> futures = new ArrayList<>();
        when(producer.sendLotteryInvoice(any())).thenAnswer(invocation -> {
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
        jdbc.update("UPDATE user_strategy_export SET stock_mq_state=1,mq_state=1,grant_state=0,update_time=DATE_SUB(NOW(),INTERVAL 11 MINUTE)");
        KafkaProducer producer = mock(KafkaProducer.class);
        SettableListenableFuture<SendResult<String, Object>> future = new SettableListenableFuture<>();
        when(producer.sendLotteryInvoice(any())).thenReturn(future);
        job(producer).lotteryOrderMQStateJobHandler();
        verify(producer).sendLotteryInvoice(any());
        future.set(null);
        consumeInvoice();
        assertTrue(repository.isAwardCompleted("mq-test", 1L));
        assertTrue(repository.scanInvoiceMqState(10).isEmpty());
    }

    @Test
    public void concurrentSqlUpdatesNeverUndoConsumption() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            // 每轮真正由独立连接竞争同一行锁，Future.get 会传播工作线程异常。
            for (int round = 0; round < 20; round++) {
                jdbc.update("UPDATE user_strategy_export SET stock_mq_state=0,mq_state=0,grant_state=1");
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> actions = new ArrayList<>();
                for (int index = 0; index < 12; index++) {
                    final int event = index % 4;
                    actions.add(pool.submit(() -> {
                        start.await();
                        if (event == 3) {
                            repository.markStockSettlementFailed("mq-test", 1L);
                        } else {
                            repository.updateStockMqState("mq-test", 1L, new int[]{1, 2, 3}[event]);
                        }
                        repository.updateInvoiceMqState("mq-test", 1L, new int[]{1, 2, 3, 2}[event]);
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
        IDBRouterStrategy router = mock(IDBRouterStrategy.class);
        when(router.dbCount()).thenReturn(1);
        when(router.tbCount()).thenReturn(1);
        ReflectionTestUtils.setField(repository, "dbRouter", router);
        IStrategyAwardStockMqGuard guard = mock(IStrategyAwardStockMqGuard.class);
        when(guard.tryClaimDispatch(1L)).thenReturn(true);
        LotteryXxlJob job = new LotteryXxlJob();
        ReflectionTestUtils.setField(job, "awardOrder", awardOrder());
        ReflectionTestUtils.setField(job, "kafkaProducer", producer);
        ReflectionTestUtils.setField(job, "strategyAwardStockMqGuard", guard);
        ReflectionTestUtils.setField(job, "stockSettlementDispatchBatchSize", 200);
        ReflectionTestUtils.setField(job, "invoiceMqEnabled", true);
        XxlJobContext.setXxlJobContext(new XxlJobContext(1, "1", null, 0, 1));
        return job;
    }

    private AwardOrderImpl awardOrder() {
        AwardOrderImpl order = new AwardOrderImpl();
        ReflectionTestUtils.setField(order, "orderRepository", repository);
        return order;
    }

    private void consumeStock(KafkaProducer producer, boolean firstConsumption) {
        IStrategyRepository strategy = mock(IStrategyRepository.class);
        when(strategy.settleAwardStock(any())).thenReturn(true);
        LotteryStrategyAwardStockListener listener = new LotteryStrategyAwardStockListener();
        ReflectionTestUtils.setField(listener, "awardOrder", awardOrder());
        ReflectionTestUtils.setField(listener, "strategyRepository", strategy);
        ReflectionTestUtils.setField(listener, "kafkaProducer", producer);
        // 库存用例关闭后续发奖，避免混入第二条消息链；发奖链另有完整用例。
        ReflectionTestUtils.setField(listener, "invoiceMqEnabled", false);
        AwardStockSettlementVO stock = new AwardStockSettlementVO();
        stock.setuId("mq-test");
        stock.setOrderId(1L);
        stock.setStrategyId(10001L);
        stock.setAwardId("C");
        Acknowledgment ack = mock(Acknowledgment.class);
        listener.onMessage(new ConsumerRecord<>("lottery_strategy_award_stock", 0, 0, "1", JSON.toJSONString(stock)),
                ack, "lottery_strategy_award_stock");
        verify(ack).acknowledge();
        verify(strategy, times(firstConsumption ? 1 : 0)).settleAwardStock(any());
    }

    private void consumeInvoice() {
        IInvoiceMqProcessGuard guard = mock(IInvoiceMqProcessGuard.class);
        when(guard.tryClaimProcessing(1L)).thenReturn(true);
        CouponFulfillmentHandler handler = new CouponFulfillmentHandler();
        ReflectionTestUtils.setField(handler, "orderRepository", repository);
        LotteryInvoiceListener listener = new LotteryInvoiceListener();
        ReflectionTestUtils.setField(listener, "orderRepository", repository);
        ReflectionTestUtils.setField(listener, "invoiceMqProcessGuard", guard);
        ReflectionTestUtils.setField(listener, "fulfillmentHandlers", Collections.singletonList(handler));
        listener.initRegistry();
        InvoiceVO invoice = repository.queryInvoiceByOrderId("mq-test", 1L);
        Acknowledgment ack = mock(Acknowledgment.class);
        listener.onMessage(new ConsumerRecord<>("lottery_invoice", 0, 0, "1", JSON.toJSONString(invoice)), ack, "lottery_invoice");
        verify(ack).acknowledge();
    }

    private UserStrategyExport request() {
        UserStrategyExport request = new UserStrategyExport();
        request.setuId("mq-test");
        request.setOrderId(1L);
        return request;
    }

    private int invoiceState() {
        return jdbc.queryForObject("SELECT mq_state FROM user_strategy_export WHERE order_id=1", Integer.class);
    }

    private Date updateTime() {
        return jdbc.queryForObject("SELECT update_time FROM user_strategy_export WHERE order_id=1", Date.class);
    }

    private int stockState() {
        return jdbc.queryForObject("SELECT stock_mq_state FROM user_strategy_export WHERE order_id=1", Integer.class);
    }
}
