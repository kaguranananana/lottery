package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.infrastructure.dao.IDrawOrderDao;
import com.anontokyo.lottery.infrastructure.repository.OrderRepository;
import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.application.mq.consumer.AwardFulfillmentListener;
import com.anontokyo.lottery.application.mq.consumer.StockSettlementListener;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository.OutboxKind;
import com.anontokyo.lottery.application.mq.producer.OutboxDispatcher;
import com.anontokyo.lottery.domain.award.service.fulfillment.impl.CouponFulfillmentHandler;
import com.anontokyo.lottery.domain.draw.repository.IDrawConfigRepository;
import com.anontokyo.lottery.infrastructure.po.DrawOrder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
                + "fulfillment_event_status INT, stock_settlement_event_status INT, stock_retry_at DATETIME(3), stock_attempt INT DEFAULT 0,"
                + "fulfillment_retry_at DATETIME(3), fulfillment_attempt INT DEFAULT 0, stock_settled_at DATETIME(3),"
                + "create_time DATETIME(3), update_time DATETIME) ENGINE=InnoDB");
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

    @Before
    public void order() {
        // 清理的是本测试 schema 的临时表，避免用例共享状态。
        jdbc.update("DELETE FROM draw_order");
        jdbc.update("INSERT INTO draw_order (u_id,order_id,draw_config_id,award_id,award_type,award_fulfillment_status,"
                + "stock_settlement_event_status,fulfillment_event_status,stock_retry_at,create_time,update_time) VALUES ('mq-test',1,10001,'C',3,0,0,0,NOW(3),NOW(),NOW())");
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
        int[][] expected = {{0, 1, 2, 3, 0}, {1, 1, 1, 1, 1}, {2, 1, 2, 3, 2}, {3, 1, 3, 3, 3}, {4, 4, 4, 4, 4}};
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
    public void stockGapBlocksCallbacksAndRequiresReconciliation() {
        for (int from = 0; from < 5; from++) {
            jdbc.update("UPDATE draw_order SET stock_settlement_event_status=?", from);
            repository.markStockSettlementFailed("mq-test", 1L);
            assertEquals(from == 1 ? 1 : 4, stockState());
            if (from != 1) {
                // 业务分歧必须先经过显式对账释放。
                repository.updateStockSettlementEventStatus("mq-test", 1L, 3);
                assertEquals(4, stockState());
                repository.updateStockSettlementEventStatus("mq-test", 1L, 2);
                assertEquals(4, stockState());
                repository.updateStockSettlementEventStatus("mq-test", 1L, 1);
                assertEquals(4, stockState());
            }
        }
    }

    @Test
    public void duplicateAndLateEventsDoNotPostponeTimeout() {
        assertTrue(repository.claimOutbox(OutboxKind.STOCK, 1, 0, true, 45));
        expire("stock");
        assertTrue(repository.claimOutbox(OutboxKind.STOCK, 1, 1, false, 45));
        repository.finishOutbox(OutboxKind.STOCK, 1, 2, true, 600);
        java.sql.Timestamp deadline = retry("stock");
        repository.finishOutbox(OutboxKind.STOCK, 1, 1, false, 5);
        repository.finishOutbox(OutboxKind.STOCK, 1, 1, true, 600);
        assertEquals(3, stockState());
        assertEquals(deadline, retry("stock"));
    }

    @Test
    public void realScanSqlRecoversDueStatesAndExcludesTerminalOrders() {
        for (int state : Arrays.asList(0, 2, 3, 1, 4)) {
            jdbc.update("UPDATE draw_order SET stock_settlement_event_status=?,stock_retry_at=TIMESTAMPADD(MINUTE,5,NOW(3))", state);
            assertTrue(repository.scanOutbox(OutboxKind.STOCK, 10).isEmpty());
            expire("stock");
            assertEquals(state == 1 || state == 4 ? 0 : 1, repository.scanOutbox(OutboxKind.STOCK, 10).size());
        }
        jdbc.update("UPDATE draw_order SET stock_settlement_event_status=1,fulfillment_event_status=2,award_fulfillment_status=0");
        expire("fulfillment");
        assertEquals(1, repository.scanOutbox(OutboxKind.FULFILLMENT, 10).size());
        jdbc.update("UPDATE draw_order SET award_fulfillment_status=1");
        assertTrue(repository.scanOutbox(OutboxKind.FULFILLMENT, 10).isEmpty());
    }

    @Test
    public void stockConsumerCompletesBeforeProducerCallbacks() {
        assertTrue(repository.claimOutbox(OutboxKind.STOCK, 1, 0, true, 45));
        consumeStock(true);
        assertEquals(1, stockState());
        repository.finishOutbox(OutboxKind.STOCK, 1, 1, true, 600);
        repository.finishOutbox(OutboxKind.STOCK, 1, 1, false, 5);
        assertEquals(1, stockState());
        assertNull(retry("stock"));
        consumeStock(false);
    }

    @Test
    public void realtimeAndRecoveryCannotBothClaimSameGeneration() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> realtime = pool.submit(() -> {
                start.await(); return repository.claimOutbox(OutboxKind.STOCK, 1, 0, true, 45);
            });
            Future<Boolean> recovery = pool.submit(() -> {
                start.await(); return repository.claimOutbox(OutboxKind.STOCK, 1, 0, false, 45);
            });
            start.countDown();
            assertNotEquals(realtime.get(10, TimeUnit.SECONDS), recovery.get(10, TimeUnit.SECONDS));
            assertTrue(repository.scanOutbox(OutboxKind.STOCK, 10).isEmpty());
            // 模拟领取后进程消失。只有到期后下一代可以领取。
            expire("stock");
            assertTrue(repository.claimOutbox(OutboxKind.STOCK, 1, 1, false, 45));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void timedOutStockFailureKeepsSentAndUsesBackoff() {
        jdbc.update("UPDATE draw_order SET stock_settlement_event_status=3");
        assertTrue(repository.claimOutbox(OutboxKind.STOCK, 1, 0, false, 45));
        repository.finishOutbox(OutboxKind.STOCK, 1, 1, false, 5);
        assertEquals(3, stockState());
        assertTrue(repository.scanOutbox(OutboxKind.STOCK, 10).isEmpty());
        expire("stock");
        assertTrue(repository.claimOutbox(OutboxKind.STOCK, 1, 1, false, 45));
        repository.finishOutbox(OutboxKind.STOCK, 1, 2, true, 600);
        consumeStock(true);
        assertEquals(1, stockState());
    }

    @Test
    public void invoiceConsumerAndLateCallbacksShareRealCas() {
        jdbc.update("UPDATE draw_order SET stock_settlement_event_status=1,fulfillment_event_status=2");
        expire("fulfillment");
        assertTrue(repository.claimOutbox(OutboxKind.FULFILLMENT, 1, 0, false, 45));
        consumeInvoice();
        repository.finishOutbox(OutboxKind.FULFILLMENT, 1, 1, true, 600);
        assertEquals(1, invoiceState());
        assertTrue(repository.isAwardCompleted("mq-test", 1L));
        assertNull(retry("fulfillment"));
        consumeInvoice();
        assertTrue(repository.scanOutbox(OutboxKind.FULFILLMENT, 10).isEmpty());
    }

    @Test
    public void invoiceRetriesIgnoreOldFailuresAfterNewSuccess() {
        jdbc.update("UPDATE draw_order SET stock_settlement_event_status=1,fulfillment_event_status=2");
        expire("fulfillment");
        assertTrue(repository.claimOutbox(OutboxKind.FULFILLMENT, 1, 0, false, 45));
        expire("fulfillment");
        assertTrue(repository.claimOutbox(OutboxKind.FULFILLMENT, 1, 1, false, 45));
        repository.finishOutbox(OutboxKind.FULFILLMENT, 1, 2, true, 600);
        java.sql.Timestamp deadline = retry("fulfillment");
        repository.finishOutbox(OutboxKind.FULFILLMENT, 1, 1, false, 5);
        assertEquals(3, invoiceState());
        assertEquals(deadline, retry("fulfillment"));
        consumeInvoice();
        assertEquals(1, invoiceState());
    }

    @Test
    public void legacyInvoiceCompleteWithoutGrantIsRecoverableAfterMigration() throws Exception {
        jdbc.update("UPDATE draw_order SET stock_settlement_event_status=1,fulfillment_event_status=1,award_fulfillment_status=0,"
                + "update_time=DATE_SUB(NOW(),INTERVAL 11 MINUTE)");
        String migration = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("../docs/sql/V7__outbox_retry.sql")),
                java.nio.charset.StandardCharsets.UTF_8);
        jdbc.execute(migration.split("-- BEGIN OUTBOX BACKFILL\n")[1]);
        assertEquals(1, repository.scanOutbox(OutboxKind.FULFILLMENT, 10).size());
        consumeInvoice();
        assertTrue(repository.isAwardCompleted("mq-test", 1L));
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
                            repository.updateStockSettlementEventStatus("mq-test", 1L, 1);
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

    private void consumeStock(boolean expected) {
        IDrawConfigRepository config = mock(IDrawConfigRepository.class);
        when(config.settleAwardStock(any())).thenReturn(true);
        StockSettlementListener listener = new StockSettlementListener();
        ReflectionTestUtils.setField(listener, "orderRepository", repository);
        ReflectionTestUtils.setField(listener, "drawConfigRepository", config);
        ReflectionTestUtils.setField(listener, "outbox", mock(OutboxDispatcher.class));
        Acknowledgment ack = mock(Acknowledgment.class);
        AwardStockSettlementVO stock = new AwardStockSettlementVO();
        stock.setuId("mq-test");
        stock.setOrderId(1L);
        listener.onMessage(new ConsumerRecord<>("lottery_strategy_award_stock", 0, 0, "1", JSON.toJSONString(stock)),
                ack, "lottery_strategy_award_stock");
        verify(ack).acknowledge();
        verify(config, times(expected ? 1 : 0)).settleAwardStock(any());
    }

    private void consumeInvoice() {
        CouponFulfillmentHandler handler = new CouponFulfillmentHandler();
        ReflectionTestUtils.setField(handler, "orderRepository", repository);
        AwardFulfillmentListener listener = new AwardFulfillmentListener();
        ReflectionTestUtils.setField(listener, "orderRepository", repository);
        ReflectionTestUtils.setField(listener, "fulfillmentHandlers", Collections.singletonList(handler));
        listener.initRegistry();
        AwardFulfillmentEvent invoice = new AwardFulfillmentEvent();
        invoice.setuId("mq-test"); invoice.setOrderId(1L);
        Acknowledgment ack = mock(Acknowledgment.class);
        listener.onMessage(new ConsumerRecord<>("lottery_invoice", 0, 0, "1", JSON.toJSONString(invoice)), ack, "lottery_invoice");
        verify(ack).acknowledge();
    }

    private void expire(String kind) {
        jdbc.update("UPDATE draw_order SET " + kind + "_retry_at=TIMESTAMPADD(SECOND,-1,NOW(3))");
    }

    private java.sql.Timestamp retry(String kind) {
        return jdbc.queryForObject("SELECT " + kind + "_retry_at FROM draw_order WHERE order_id=1", java.sql.Timestamp.class);
    }

    private int invoiceState() {
        return jdbc.queryForObject("SELECT fulfillment_event_status FROM draw_order WHERE order_id=1", Integer.class);
    }

    private int stockState() {
        return jdbc.queryForObject("SELECT stock_settlement_event_status FROM draw_order WHERE order_id=1", Integer.class);
    }
}
