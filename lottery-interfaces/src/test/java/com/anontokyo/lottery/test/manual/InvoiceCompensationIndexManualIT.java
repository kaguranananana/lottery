package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.infrastructure.dao.IDrawOrderDao;
import com.anontokyo.lottery.infrastructure.po.DrawOrder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** 真实 MySQL 验证原补偿 Mapper 的候选集合和索引读取量，不以耗时阈值判断正确性。 */
public class InvoiceCompensationIndexManualIT {
    private static final String SCHEMA = "lottery_perf_invoice_scan_it";
    private static DriverManagerDataSource source;
    private static JdbcTemplate jdbc;
    private static SqlSessionFactory factory;
    private static IDrawOrderDao dao;

    @BeforeClass
    public static void database() throws Exception {
        String url = System.getProperty("lottery.perf.mysql");
        // 必须显式选择专用压测端口，只建立本测试拥有的 schema。
        assertTrue("须指定独立测试容器的本机 JDBC 地址",
                url != null && url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/"));
        try (Connection c = DriverManager.getConnection(url, "root", "perf-only"); Statement s = c.createStatement()) {
            // 库已存在则失败，避免覆盖其它会话留下的证据。
            s.execute("CREATE DATABASE " + SCHEMA);
        }
        source = new DriverManagerDataSource(url + SCHEMA + "?useSSL=false&allowPublicKeyRetrieval=true", "root", "perf-only");
        jdbc = new JdbcTemplate(source);
        // 字段覆盖生产扫描投影与条件，旧库存状态索引用于复现历史读放大。
        jdbc.execute("CREATE TABLE draw_order (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "u_id VARCHAR(32),order_id BIGINT,take_id BIGINT,activity_id BIGINT,award_id VARCHAR(64),award_type INT,"
                + "award_name VARCHAR(64),award_content VARCHAR(128),"
                + "award_fulfillment_status TINYINT,fulfillment_event_status TINYINT,stock_settlement_event_status TINYINT,create_time DATETIME,update_time DATETIME,"
                + "fulfillment_retry_at DATETIME(3), stock_attempt INT DEFAULT 0, fulfillment_attempt INT DEFAULT 0,"
                + "INDEX idx_stock_settlement_event_status_create_time(stock_settlement_event_status,create_time)) ENGINE=InnoDB");
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(source);
        // 直接装配生产 XML，不能复制一个近似条件替代被测查询。
        bean.setMapperLocations(new ClassPathResource("mybatis/mapper/DrawOrder_Mapper.xml"));
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(configuration);
        factory = bean.getObject();
        dao = new SqlSessionTemplate(factory).getMapper(IDrawOrderDao.class);
    }

    @AfterClass
    public static void cleanup() {
        // jdbc 只在成功创建自有 schema 后赋值，清理不涉及业务三库。
        if (jdbc != null) jdbc.execute("DROP DATABASE " + SCHEMA);
    }

    @Before
    public void history() throws Exception {
        // 每个用例重建自有表数据和索引状态，前后计划使用同一份数据。
        jdbc.execute("TRUNCATE draw_order");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=?"
                + " AND table_name='draw_order' AND index_name='idx_fulfillment_retry'", Integer.class, SCHEMA) > 0) {
            jdbc.execute("ALTER TABLE draw_order DROP INDEX idx_fulfillment_retry");
        }
        try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO draw_order(u_id,order_id,award_fulfillment_status,stock_settlement_event_status,fulfillment_event_status,create_time,update_time)"
                        + " VALUES ('scan-history',?,1,1,1,NOW(),NOW())")) {
            // 大部分订单已完成，用批量 fixture 重现 LIMIT 100 无候选时仍扫描历史的条件。
            for (int i = 1; i <= 5000; i++) {
                s.setLong(1, i);
                s.addBatch();
                if (i % 500 == 0) s.executeBatch();
            }
        }
        jdbc.execute("ANALYZE TABLE draw_order");
    }

    @Test
    public void emptyScanStopsReadingCompletedHistory() throws Exception {
        // 原 SQL 无候选仍读取至少五千行，读取工作量独立于宿主机耗时波动。
        assertTrue(dao.scanFulfillmentEventStatus(100).isEmpty());
        assertTrue(scannedRows(plan()) >= 5000);
        applyMigration();
        // 原谓词不变，新索引使无候选时不再读取已发奖订单。
        assertTrue(dao.scanFulfillmentEventStatus(100).isEmpty());
        String after = plan();
        assertTrue(after, after.contains("idx_fulfillment_retry"));
        assertEquals(after, 0, scannedRows(after));
        assertEquals(5000, jdbc.queryForObject("SELECT COUNT(*) FROM draw_order", Integer.class).intValue());
    }

    @Test
    public void indexPreservesAllCompensationEligibility() throws Exception {
        // 退避时间独立于订单 update_time。到期未完成候选才可投递。
        insert(6001, 1, 0, 2, -1);
        insert(6002, 1, 0, 0, -1);
        insert(6003, 1, 0, 3, -1);
        insert(6004, 1, 2, 2, -1);
        insert(6005, 1, null, 2, -1);
        insert(6011, 1, 0, 0, 10);
        insert(6012, 1, 0, 2, 10);
        insert(6013, 1, 0, 3, 10);
        insert(6014, 0, 0, 2, -1);
        insert(6015, 4, 0, 2, -1);
        insert(6016, 1, 1, 2, -1);
        insert(6017, 1, 1, 1, -1);
        Set<Long> expected = new HashSet<>(Arrays.asList(6001L, 6002L, 6003L, 6004L, 6005L));
        Set<Long> before = orders();
        assertEquals(expected, before);
        assertTrue(scannedRows(plan()) >= 5000);
        applyMigration();
        // 同一 fixture、同一 Mapper、相同返回集合，计划只遍历少量未发奖订单。
        assertEquals(before, orders());
        String after = plan();
        assertTrue(after, after.contains("idx_fulfillment_retry"));
        assertTrue(after, scannedRows(after) <= 10);
        assertEquals(5012, jdbc.queryForObject("SELECT COUNT(*) FROM draw_order", Integer.class).intValue());
    }

    private void insert(long order, int stock, Integer grant, int mq, int retryMinutes) {
        jdbc.update("INSERT INTO draw_order(order_id,stock_settlement_event_status,award_fulfillment_status,fulfillment_event_status,fulfillment_retry_at,create_time,update_time)"
                        + " VALUES (?,?,?,?,TIMESTAMPADD(MINUTE,?,NOW(3)),NOW(),NOW())",
                order, stock, grant, mq, retryMinutes);
    }

    private Set<Long> orders() {
        Set<Long> result = new HashSet<>();
        // 原查询没有排序契约，按订单集合比较，避免依赖物理索引顺序。
        for (DrawOrder row : dao.scanFulfillmentEventStatus(100)) result.add(row.getOrderId());
        return result;
    }

    private void applyMigration() throws Exception {
        // 对生产 DDL 中同一索引做有无对照，验证查询结果及实际扫描行数。
        jdbc.execute("ALTER TABLE draw_order ADD KEY idx_fulfillment_retry (fulfillment_retry_at,order_id)");
    }

    private String plan() {
        // BoundSql 来自生产 Mapper，唯一占位符是本次扫描上限。
        BoundSql bound = factory.getConfiguration().getMappedStatement(IDrawOrderDao.class.getName()
                + ".scanFulfillmentEventStatus").getBoundSql(Collections.singletonMap("limit", 100));
        return jdbc.queryForObject("EXPLAIN ANALYZE " + bound.getSql().replace("?", "100"), String.class);
    }

    private int scannedRows(String plan) {
        Matcher stages = Pattern.compile("actual time=[\\d.]+\\.\\.[\\d.]+ rows=(\\d+) loops=(\\d+)").matcher(plan);
        int rows = -1;
        // 该单表计划最深层是表/索引迭代器，不能将 LIMIT 的返回行数当底层读取量。
        while (stages.find()) rows = Integer.parseInt(stages.group(1)) * Integer.parseInt(stages.group(2));
        assertTrue("未获得实际执行行数：" + plan, rows >= 0);
        return rows;
    }
}
