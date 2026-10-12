package com.anontokyo.lottery.test.manual;

import cn.bugstack.middleware.db.router.strategy.IDBRouterStrategy;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.infrastructure.dao.*;
import com.anontokyo.lottery.infrastructure.po.UserTakeActivityCount;
import com.anontokyo.lottery.infrastructure.repository.StrategyRepository;
import com.anontokyo.lottery.infrastructure.repository.UserTakeActivityRepository;
import org.junit.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 只在 lottery-perf 端口建立专属 fixture，执行真实 Mapper、Repository 和事务。 */
public class PerformanceSqlManualIT {
    private static final String SCHEMA = "lottery_perf_sql_it";
    private static final AtomicLong IDS = new AtomicLong(1);
    private static DriverManagerDataSource datasource;
    private static SqlSessionTemplate session;
    private static TransactionTemplate transaction;
    private static UserTakeActivityRepository takes;
    private static StrategyRepository strategies;

    @BeforeClass public static void setup() throws Exception {
        String url = System.getProperty("lottery.perf.mysql");
        assertEquals("必须显式选择专用压测端口", "jdbc:mysql://127.0.0.1:13329/", url);
        // 仅创建测试自有 schema，不重建 lottery 或历史压测数据。
        try (Connection c = DriverManager.getConnection(url, "root", "perf-only"); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
            s.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".user_take_activity_count LIKE lottery_01.user_take_activity_count");
            s.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".user_take_activity LIKE lottery_01.user_take_activity");
            s.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".strategy_detail LIKE lottery.strategy_detail");
            s.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".award_stock_settlement LIKE lottery.award_stock_settlement");
        }
        datasource = new DriverManagerDataSource(url + SCHEMA + "?useSSL=false&allowPublicKeyRetrieval=true", "root", "perf-only");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(datasource);
        factory.setTypeAliasesPackage("com.anontokyo.lottery.infrastructure.po");
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(config);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:mybatis/mapper/*_Mapper.xml"));
        session = new SqlSessionTemplate(factory.getObject());
        transaction = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        takes = new UserTakeActivityRepository();
        ReflectionTestUtils.setField(takes, "userTakeActivityCountDao", session.getMapper(IUserTakeActivityCountDao.class));
        ReflectionTestUtils.setField(takes, "userTakeActivityDao", session.getMapper(IUserTakeActivityDao.class));
        // 该用例聚焦单库锁，分片行为另由真实 HTTP 幂等审计覆盖。
        ReflectionTestUtils.setField(takes, "dbRouter", mock(IDBRouterStrategy.class));
        ReflectionTestUtils.setField(takes, "transactionTemplate", transaction);
        strategies = new StrategyRepository();
        ReflectionTestUtils.setField(strategies, "strategyDetailDao", session.getMapper(IStrategyDetailDao.class));
        ReflectionTestUtils.setField(strategies, "awardStockSettlementDao", session.getMapper(IAwardStockSettlementDao.class));
        ReflectionTestUtils.setField(strategies, "transactionTemplate", transaction);
    }

    @Before public void cleanFixture() throws Exception {
        // 测试之间只清空本类创建的四张表，避免旧数据影响锁位置。
        execute("TRUNCATE user_take_activity_count");
        execute("TRUNCATE user_take_activity");
        execute("TRUNCATE strategy_detail");
        execute("TRUNCATE award_stock_settlement");
    }

    private static void execute(String sql) throws Exception {
        try (Connection c = datasource.getConnection(); Statement s = c.createStatement()) { s.execute(sql); }
    }
    private static long number(String sql) throws Exception {
        try (Connection c = datasource.getConnection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            assertTrue(r.next()); return r.getLong(1);
        }
    }
    private static ActivityBillVO bill(String user, long activity, int quota) {
        ActivityBillVO b = new ActivityBillVO();
        b.setuId(user); b.setActivityId(activity); b.setTakeCount(quota);
        b.setActivityName("SQL 性能回归"); b.setStrategyId(activity);
        return b;
    }
    private static UserTakeActivityCount count(String user) {
        UserTakeActivityCount c = new UserTakeActivityCount();
        c.setuId(user); c.setActivityId(1L); c.setTotalCount(10);
        return c;
    }

    @Test public void existingUsersMustNotBlockOnPrimarySupremum() throws Exception {
        execute("INSERT INTO user_take_activity_count(u_id,activity_id,total_count,left_count) VALUES ('userA',1,10,10),('userB',1,10,10)");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Connection held = datasource.getConnection()) {
            held.setAutoCommit(false);
            // 保持真实无变化 upsert 的 supremum 锁，模拟尚未完成的参与事务。
            try (Statement s = held.createStatement()) {
                s.executeUpdate("INSERT INTO user_take_activity_count(u_id,activity_id,total_count,left_count) VALUES ('userA',1,10,10) ON DUPLICATE KEY UPDATE u_id=u_id");
            }
            try {
                Future<Constants.ResponseCode> other = worker.submit(() -> takes.takeParticipation(bill("userB", 1, 10), new java.util.Date(), IDS.incrementAndGet(), "existing"));
                // userB 已有次数行，应在 userA 提交之前完成，不能等待无关主键末端间隙。
                assertEquals(Constants.ResponseCode.SUCCESS, other.get(1, TimeUnit.SECONDS));
            } finally {
                held.rollback();
            }
        } finally {
            worker.shutdown(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(9, number("SELECT left_count FROM user_take_activity_count WHERE u_id='userB'"));
    }

    @Test public void firstParticipationAndExhaustionKeepQuotaSemantics() throws Exception {
        ActivityBillVO b = bill("newUser", 2, 1);
        assertEquals(Constants.ResponseCode.SUCCESS, takes.takeParticipation(b, new java.util.Date(), IDS.incrementAndGet(), "first"));
        assertEquals(Constants.ResponseCode.NO_UPDATE, takes.takeParticipation(b, new java.util.Date(), IDS.incrementAndGet(), "second"));
        assertEquals(0, number("SELECT left_count FROM user_take_activity_count"));
        assertEquals(1, number("SELECT COUNT(*) FROM user_take_activity"));
    }

    @Test public void duplicateRequestRollsBackQuota() throws Exception {
        ActivityBillVO b = bill("replayUser", 3, 10);
        long take = IDS.incrementAndGet();
        assertEquals(Constants.ResponseCode.SUCCESS, takes.takeParticipation(b, new java.util.Date(), take, "same"));
        assertEquals(Constants.ResponseCode.SUCCESS, takes.consumeTake("replayUser", 3L, take));
        assertEquals(Constants.ResponseCode.INDEX_DUP, takes.takeParticipation(b, new java.util.Date(), IDS.incrementAndGet(), "same"));
        assertEquals(9, number("SELECT left_count FROM user_take_activity_count"));
        assertEquals(1, number("SELECT COUNT(*) FROM user_take_activity"));
    }

    @Test public void concurrentFirstParticipationKeepsSingleUnfinishedTake() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(1);
        List<Future<Constants.ResponseCode>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                final int request = i;
                futures.add(workers.submit(() -> {
                    ready.await();
                    return takes.takeParticipation(bill("concurrentUser", 4, 10), new java.util.Date(), IDS.incrementAndGet(), "request" + request);
                }));
            }
            ready.countDown();
            int successes = 0;
            for (Future<Constants.ResponseCode> future : futures) {
                Constants.ResponseCode code = future.get(10, TimeUnit.SECONDS);
                assertTrue(code == Constants.ResponseCode.SUCCESS || code == Constants.ResponseCode.INDEX_DUP);
                if (code == Constants.ResponseCode.SUCCESS) successes++;
            }
            assertEquals(1, successes);
        } finally { workers.shutdownNow(); }
        assertEquals(9, number("SELECT left_count FROM user_take_activity_count"));
        assertEquals(1, number("SELECT COUNT(*) FROM user_take_activity"));
    }

    @Test public void settlementStillAtomicAndIdempotent() throws Exception {
        execute("INSERT INTO strategy_detail(strategy_id,award_id,award_count,award_surplus_count) VALUES (5,'A',1,1)");
        AwardStockSettlementVO first = settlement(1L);
        assertTrue(strategies.settleAwardStock(first));
        assertTrue(strategies.settleAwardStock(first));
        assertFalse(strategies.settleAwardStock(settlement(2L)));
        assertEquals(0, number("SELECT award_surplus_count FROM strategy_detail"));
        assertEquals(1, number("SELECT COUNT(*) FROM award_stock_settlement"));
    }
    private static AwardStockSettlementVO settlement(long order) {
        AwardStockSettlementVO vo = new AwardStockSettlementVO();
        vo.setOrderId(order); vo.setuId("settlementUser"); vo.setStrategyId(5L); vo.setAwardId("A");
        return vo;
    }

}
