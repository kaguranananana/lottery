package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.infrastructure.dao.IUserStrategyExportDao;
import com.anontokyo.lottery.infrastructure.po.UserStrategyExport;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
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
    private static IUserStrategyExportDao dao;

    @BeforeClass
    public static void database() throws Exception {
        String url = System.getProperty("lottery.perf.mysql");
        // 必须显式选择专用压测端口，只建立本测试拥有的 schema。
        assertEquals("jdbc:mysql://127.0.0.1:13329/", url);
        try (Connection c = DriverManager.getConnection(url, "root", "perf-only"); Statement s = c.createStatement()) {
            // 库已存在则失败，避免覆盖其它会话留下的证据。
            s.execute("CREATE DATABASE " + SCHEMA);
        }
        source = new DriverManagerDataSource(url + SCHEMA + "?useSSL=false&allowPublicKeyRetrieval=true", "root", "perf-only");
        jdbc = new JdbcTemplate(source);
        // 字段覆盖生产扫描投影与条件，旧库存状态索引用于复现历史读放大。
        jdbc.execute("CREATE TABLE user_strategy_export (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "u_id VARCHAR(32),order_id BIGINT,take_id BIGINT,award_id VARCHAR(64),award_type INT,"
                + "award_name VARCHAR(64),award_content VARCHAR(128),grant_type INT,grant_date DATETIME,"
                + "grant_state TINYINT,mq_state TINYINT,stock_mq_state TINYINT,create_time DATETIME,update_time DATETIME,"
                + "INDEX idx_stock_mq_state_create_time(stock_mq_state,create_time)) ENGINE=InnoDB");
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(source);
        // 直接装配生产 XML，不能复制一个近似条件替代被测查询。
        bean.setMapperLocations(new ClassPathResource("mybatis/mapper/UserStrategyExport_Mapper.xml"));
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(configuration);
        factory = bean.getObject();
        dao = new SqlSessionTemplate(factory).getMapper(IUserStrategyExportDao.class);
    }

    @AfterClass
    public static void cleanup() {
        // jdbc 只在成功创建自有 schema 后赋值，清理不涉及业务三库。
        if (jdbc != null) jdbc.execute("DROP DATABASE " + SCHEMA);
    }

    @Before
    public void history() throws Exception {
        // 每个用例重建自有表数据和索引状态，前后计划使用同一份数据。
        jdbc.execute("TRUNCATE user_strategy_export");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=?"
                + " AND table_name='user_strategy_export' AND index_name='idx_invoice_grant_state'", Integer.class, SCHEMA) > 0) {
            jdbc.execute("ALTER TABLE user_strategy_export DROP INDEX idx_invoice_grant_state");
        }
        try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO user_strategy_export(u_id,order_id,grant_state,stock_mq_state,mq_state,create_time,update_time)"
                        + " VALUES ('scan-history',?,1,1,1,NOW(),NOW())")) {
            // 大部分订单已完成，用批量 fixture 重现 LIMIT 100 无候选时仍扫描历史的条件。
            for (int i = 1; i <= 5000; i++) {
                s.setLong(1, i);
                s.addBatch();
                if (i % 500 == 0) s.executeBatch();
            }
        }
        jdbc.execute("ANALYZE TABLE user_strategy_export");
    }

    @Test
    public void emptyScanStopsReadingCompletedHistory() throws Exception {
        // 原 SQL 无候选仍读取至少五千行，读取工作量独立于宿主机耗时波动。
        assertTrue(dao.scanInvoiceMqState(100).isEmpty());
        assertTrue(scannedRows(plan()) >= 5000);
        applyMigration();
        // 原谓词不变，新索引使无候选时不再读取已发奖订单。
        assertTrue(dao.scanInvoiceMqState(100).isEmpty());
        String after = plan();
        assertTrue(after, after.contains("idx_invoice_grant_state"));
        assertEquals(after, 0, scannedRows(after));
        assertEquals(5000, jdbc.queryForObject("SELECT COUNT(*) FROM user_strategy_export", Integer.class).intValue());
    }

    @Test
    public void indexPreservesAllCompensationEligibility() throws Exception {
        // FAIL 立即补偿；INIT 保留三十分钟；旧 COMPLETE 和 SENT 保留十分钟窗口。
        insert(6001, 1, 0, 2, 0, 0);
        insert(6002, 1, 0, 0, 31, 0);
        insert(6003, 1, 0, 1, 0, 11);
        insert(6004, 1, 0, 3, 0, 11);
        // grant_state 的真实 FAIL=2 也未完成，必须保留不等于一的第二段索引范围。
        insert(6005, 1, 2, 2, 0, 0);
        // 当前未超时的义务不提前发送，库存未结算和 BLOCKED 不进入发奖补偿。
        insert(6011, 1, 0, 0, 0, 0);
        insert(6012, 1, 0, 1, 0, 0);
        insert(6013, 1, 0, 3, 0, 0);
        insert(6014, 0, 0, 2, 31, 11);
        insert(6015, 4, 0, 2, 31, 11);
        insert(6016, 1, 1, 2, 31, 11);
        // 生产 grant_state 可空，索引不能改变 SQL 三值逻辑对 NULL 的排除。
        jdbc.update("INSERT INTO user_strategy_export(order_id,stock_mq_state,grant_state,mq_state,create_time,update_time)"
                + " VALUES (6017,1,NULL,2,NOW(),NOW())");
        Set<Long> expected = new HashSet<>(Arrays.asList(6001L, 6002L, 6003L, 6004L, 6005L));
        Set<Long> before = orders();
        assertEquals(expected, before);
        assertTrue(scannedRows(plan()) >= 5000);
        applyMigration();
        // 同一 fixture、同一 Mapper、相同返回集合，计划只遍历少量未发奖订单。
        assertEquals(before, orders());
        String after = plan();
        assertTrue(after, after.contains("idx_invoice_grant_state"));
        assertTrue(after, scannedRows(after) <= 10);
        assertEquals(5012, jdbc.queryForObject("SELECT COUNT(*) FROM user_strategy_export", Integer.class).intValue());
    }

    private void insert(long order, int stock, int grant, int mq, int createMinutes, int updateMinutes) {
        // 时间只用于既有补偿契约，使用远离边界的实际 MySQL NOW() fixture。
        jdbc.update("INSERT INTO user_strategy_export(order_id,stock_mq_state,grant_state,mq_state,create_time,update_time)"
                        + " VALUES (?,?,?,?,DATE_SUB(NOW(),INTERVAL ? MINUTE),DATE_SUB(NOW(),INTERVAL ? MINUTE))",
                order, stock, grant, mq, createMinutes, updateMinutes);
    }

    private Set<Long> orders() {
        Set<Long> result = new HashSet<>();
        // 原查询没有排序契约，按订单集合比较，避免依赖物理索引顺序。
        for (UserStrategyExport row : dao.scanInvoiceMqState(100)) result.add(row.getOrderId());
        return result;
    }

    private void applyMigration() throws Exception {
        // Maven 用例工作目录为 lottery-interfaces，从仓库迁移取第一张表的实际 DDL。
        String migration = new String(Files.readAllBytes(Paths.get("../doc/sql/20261013_invoice_compensation_index.sql")), StandardCharsets.UTF_8);
        Matcher statement = Pattern.compile("ALTER TABLE lottery_01\\.user_strategy_export_000[^;]+;").matcher(migration);
        assertTrue("迁移缺少第一张订单表", statement.find());
        // 仅把迁移的物理目标替换为测试自有表，索引定义及在线 DDL 条件保持原样。
        jdbc.execute(statement.group().replace("lottery_01.user_strategy_export_000", "user_strategy_export"));
    }

    private String plan() {
        // BoundSql 来自生产 Mapper，唯一占位符是本次扫描上限。
        BoundSql bound = factory.getConfiguration().getMappedStatement(IUserStrategyExportDao.class.getName()
                + ".scanInvoiceMqState").getBoundSql(Collections.singletonMap("limit", 100));
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
