package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.infrastructure.dao.IDrawOrderDao;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository.OutboxKind;
import com.anontokyo.lottery.infrastructure.repository.OrderRepository;
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


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.Assert.*;

/**
 * 隔离 MySQL 测试：生产 Mapper 与 Repository 的真实 SQL，不使用数据库 Mock。
 * 测试表放在专用 MySQL，直接检查订单 CAS 与到期领取。
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
                + "u_id VARCHAR(32), order_id BIGINT, take_id BIGINT, activity_id BIGINT,"
                + "fulfilled_at DATETIME, award_fulfillment_status INT, award_id VARCHAR(64), award_type INT,"
                + "award_name VARCHAR(64), award_content VARCHAR(128),"
                + "fulfillment_event_status INT, stock_settlement_event_status INT, stock_retry_at DATETIME(3),"
                + "fulfillment_retry_at DATETIME(3), stock_settled_at DATETIME(3),"
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
        jdbc.update("INSERT INTO draw_order (u_id,order_id,activity_id,award_id,award_type,award_fulfillment_status,"
                + "stock_settlement_event_status,fulfillment_event_status,stock_retry_at,create_time,update_time) VALUES ('mq-test',1,10001,'C',3,0,0,0,NOW(3),NOW(),NOW())");
    }

    @Test
    public void stockCompletionRegistersOnlyOneFulfillmentObligation() {
        assertTrue(repository.updateStockSettlementEventStatus("mq-test", 1L, 1));
        assertFalse(repository.updateStockSettlementEventStatus("mq-test", 1L, 1));
        assertNotNull(jdbc.queryForObject("SELECT fulfillment_retry_at FROM draw_order", java.sql.Timestamp.class));
        assertNull(jdbc.queryForObject("SELECT stock_retry_at FROM draw_order", java.sql.Timestamp.class));
        assertTrue(repository.scanOutbox(OutboxKind.STOCK, 10).isEmpty());
    }

    @Test
    public void blockedOrderRequiresExplicitReconciliation() {
        repository.markStockSettlementFailed("mq-test", 1L);
        repository.markStockSettlementFailed("mq-test", 1L);
        assertFalse(repository.updateStockSettlementEventStatus("mq-test", 1L, 1));
        assertFalse(repository.claimOutbox(OutboxKind.STOCK, 1L, 600));
        assertTrue(repository.scanOutbox(OutboxKind.STOCK, 10).isEmpty());
        jdbc.update("UPDATE draw_order SET stock_settlement_event_status=0, stock_retry_at=NOW(3)");
        assertEquals(1, repository.scanOutbox(OutboxKind.STOCK, 10).size());
        assertTrue(repository.updateStockSettlementEventStatus("mq-test", 1L, 1));
    }

    @Test
    public void concurrentRecoveryClaimsOnlyOneDueObligation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> claims = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                claims.add(() -> repository.claimOutbox(OutboxKind.STOCK, 1L, 600));
            }
            int won = 0;
            for (Future<Boolean> result : pool.invokeAll(claims)) {
                if (result.get()) won++;
            }
            assertEquals(1, won);
            assertTrue(repository.scanOutbox(OutboxKind.STOCK, 10).isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void fulfillmentRequiresStockCompletionAndIsIdempotent() {
        assertThrows(IllegalStateException.class, () -> repository.markUserAwardGranted("mq-test", 1L, "C"));
        repository.updateFulfillmentEventStatus("mq-test", 1L, 1);
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT fulfillment_event_status FROM draw_order", Integer.class));
        assertTrue(repository.updateStockSettlementEventStatus("mq-test", 1L, 1));
        assertTrue(repository.markUserAwardGranted("mq-test", 1L, "C"));
        assertFalse(repository.markUserAwardGranted("mq-test", 1L, "C"));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT fulfillment_event_status FROM draw_order", Integer.class));
        assertNull(jdbc.queryForObject("SELECT fulfillment_retry_at FROM draw_order", java.sql.Timestamp.class));
    }
}
