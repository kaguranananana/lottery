package com.anontokyo.lottery.test.manual;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.*;

import static org.junit.Assert.*;

/** 使用真实 MySQL、Hikari 和 Spring 事务验证会话缓存，不以延迟断言替代行为契约。 */
public class JdbcLocalSessionStateManualIT {
    private static final String SCHEMA = "lottery_perf_session_it";
    private static String url;
    private static JdbcTemplate admin;

    @BeforeClass
    public static void createFixture() throws Exception {
        String endpoint = System.getProperty("lottery.perf.mysql");
        // 与其它性能 SQL 门禁相同，必须显式选择已核实的专用实例。
        assertEquals("jdbc:mysql://127.0.0.1:13329/", endpoint);
        try (Connection c = DriverManager.getConnection(endpoint, "root", "perf-only"); Statement s = c.createStatement()) {
            // 已有同名库则失败，不能清掉其它会话的临时证据。
            s.execute("CREATE DATABASE " + SCHEMA);
        }
        url = endpoint + SCHEMA + "?useSSL=false&allowPublicKeyRetrieval=true";
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "root", "perf-only"));
        admin.execute("CREATE TABLE probe(id INT PRIMARY KEY,value INT) ENGINE=InnoDB");
    }

    @AfterClass
    public static void cleanup() {
        // admin 仅在成功创建自有库后赋值，测试清理不涉及三份业务库。
        if (admin != null) admin.execute("DROP DATABASE " + SCHEMA);
    }

    private HikariDataSource pool(boolean localState) {
        HikariConfig config = new HikariConfig();
        // 两种模式仅此属性不同，测试使用一条物理连接明确验证池复用。
        config.setJdbcUrl(url + "&useLocalSessionState=" + localState);
        config.setUsername("root");
        config.setPassword("perf-only");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        return new HikariDataSource(config);
    }

    @Test
    public void readOnlyChecksStopQueryingServerWithLocalState() throws Exception {
        for (boolean localState : new boolean[]{false, true}) {
            try (HikariDataSource pool = pool(localState); Connection c = pool.getConnection()) {
                // SHOW STATUS 不增加 Com_select，用真实会话计数观察驱动额外往返。
                long before = selectCount(c);
                for (int i = 0; i < 10; i++) assertFalse(c.isReadOnly());
                long added = selectCount(c) - before;
                // 缓存模式读取 JDBC 已维护状态，原模式确实发送至少十次服务器 SELECT。
                if (localState) assertEquals(0L, added);
                else assertTrue("原驱动只读检查未复现：" + added, added >= 10);
                System.out.println("SESSION_STATE_PROBE local=" + localState + " extraSelects=" + added);
            }
        }
    }

    @Test
    public void pooledConnectionRestoresReadOnlyAutoCommitAndIsolation() throws Exception {
        for (boolean localState : new boolean[]{false, true}) {
            admin.execute("TRUNCATE probe");
            try (HikariDataSource pool = pool(localState)) {
                long id;
                try (Connection c = pool.getConnection()) {
                    id = number(c, "SELECT CONNECTION_ID()");
                    // JDBC 设置仍传播到服务器，不能只让客户端 getter 看起来正确。
                    c.setReadOnly(true);
                    assertTrue(c.isReadOnly());
                    assertEquals(1L, number(c, "SELECT @@session.transaction_read_only"));
                }
                try (Connection c = pool.getConnection()) {
                    // 同一条物理连接归还后恢复可写，验证缓存与池重置同步。
                    assertEquals(id, number(c, "SELECT CONNECTION_ID()"));
                    assertFalse(c.isReadOnly());
                    assertEquals(0L, number(c, "SELECT @@session.transaction_read_only"));
                    c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    assertEquals("READ-COMMITTED", value(c, "SELECT @@session.transaction_isolation"));
                    c.setAutoCommit(false);
                    assertFalse(c.getAutoCommit());
                    assertEquals(0L, number(c, "SELECT @@session.autocommit"));
                    try (Statement s = c.createStatement()) {
                        s.executeUpdate("INSERT INTO probe VALUES(1,10)");
                    }
                    // 不提交即归还，Hikari 必须回滚实际数据库事务并恢复连接状态。
                }
                try (Connection c = pool.getConnection()) {
                    assertEquals(id, number(c, "SELECT CONNECTION_ID()"));
                    assertTrue(c.getAutoCommit());
                    assertEquals(1L, number(c, "SELECT @@session.autocommit"));
                    assertEquals("REPEATABLE-READ", value(c, "SELECT @@session.transaction_isolation"));
                    assertEquals(0L, number(c, "SELECT COUNT(*) FROM probe"));
                }
            }
        }
    }

    @Test
    public void springTransactionStillCommitsAndRollsBack() throws Exception {
        for (boolean localState : new boolean[]{false, true}) {
            admin.execute("TRUNCATE probe");
            try (HikariDataSource pool = pool(localState)) {
                JdbcTemplate jdbc = new JdbcTemplate(pool);
                TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
                // 显式回滚必须撤销真实写入，不能因会话缓存变成自动提交。
                tx.execute(status -> {
                    jdbc.update("INSERT INTO probe VALUES(2,20)");
                    status.setRollbackOnly();
                    return null;
                });
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM probe", Integer.class).intValue());
                // 正常事务仍发送并完成持久化提交，连接回到池后继续可读写。
                tx.execute(status -> {
                    jdbc.update("INSERT INTO probe VALUES(3,30)");
                    return null;
                });
                assertEquals(30, jdbc.queryForObject("SELECT value FROM probe WHERE id=3", Integer.class).intValue());
                try (Connection c = pool.getConnection()) {
                    assertTrue(c.getAutoCommit());
                    assertFalse(c.isReadOnly());
                    assertEquals(1L, number(c, "SELECT @@session.autocommit"));
                }
            }
        }
    }

    private long selectCount(Connection c) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SHOW SESSION STATUS LIKE 'Com_select'")) {
            assertTrue(r.next());
            return r.getLong(2);
        }
    }

    private long number(Connection c, String query) throws Exception {
        return Long.parseLong(value(c, query));
    }

    private String value(Connection c, String query) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }
}
