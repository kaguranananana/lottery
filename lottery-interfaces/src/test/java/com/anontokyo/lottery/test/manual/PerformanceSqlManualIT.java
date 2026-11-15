package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityPrizeVO;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.infrastructure.cache.TwoLevelCacheService;
import com.anontokyo.lottery.infrastructure.dao.*;
import com.anontokyo.lottery.infrastructure.po.UserActivityQuota;
import com.anontokyo.lottery.infrastructure.repository.ActivityPrizeRepository;
import com.anontokyo.lottery.infrastructure.repository.ActivityRepository;
import com.anontokyo.lottery.infrastructure.repository.DrawParticipationRepository;
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
    private static DrawParticipationRepository takes;
    private static ActivityPrizeRepository strategies;

    @BeforeClass public static void setup() throws Exception {
        String url = System.getProperty("lottery.perf.mysql");
        assertTrue("须指定独立测试容器的本机 JDBC 地址",
                url != null && url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/"));
        // 仅创建测试自有 schema，不重建 lottery 或历史压测数据。
        try (Connection c = DriverManager.getConnection(url, "root", "perf-only"); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE IF NOT EXISTS " + SCHEMA);
            s.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".user_activity_quota LIKE lottery.user_activity_quota");
            s.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".draw_participation LIKE lottery.draw_participation");
            s.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".activity_prize LIKE lottery.activity_prize");
            s.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".award_stock_settlement LIKE lottery.award_stock_settlement");
            s.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".award_stock_bucket LIKE lottery.award_stock_bucket");
        }
        // 事务仓储回归使用当前生产连接参数，验证会话缓存下的次数、账本及库存原子性。
        datasource = new DriverManagerDataSource(url + SCHEMA + "?useSSL=false&allowPublicKeyRetrieval=true&useLocalSessionState=true", "root", "perf-only");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(datasource);
        factory.setTypeAliasesPackage("com.anontokyo.lottery.infrastructure.po");
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(config);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:mybatis/mapper/*_Mapper.xml"));
        session = new SqlSessionTemplate(factory.getObject());
        transaction = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        takes = new DrawParticipationRepository();
        ReflectionTestUtils.setField(takes, "userActivityQuotaDao", session.getMapper(IUserActivityQuotaDao.class));
        ReflectionTestUtils.setField(takes, "drawParticipationDao", session.getMapper(IDrawParticipationDao.class));
        ReflectionTestUtils.setField(takes, "transactionTemplate", transaction);
        strategies = new ActivityPrizeRepository();
        ReflectionTestUtils.setField(strategies, "activityPrizeDao", session.getMapper(IActivityPrizeDao.class));
        ReflectionTestUtils.setField(strategies, "awardStockSettlementDao", session.getMapper(IAwardStockSettlementDao.class));
        ReflectionTestUtils.setField(strategies, "awardStockBucketDao", session.getMapper(IAwardStockBucketDao.class));
        ReflectionTestUtils.setField(strategies, "transactionTemplate", transaction);
    }

    @Before public void cleanFixture() throws Exception {
        // 测试之间只清空本类创建的四张表，避免旧数据影响锁位置。
        execute("TRUNCATE user_activity_quota");
        execute("TRUNCATE draw_participation");
        execute("TRUNCATE activity_prize");
        execute("TRUNCATE award_stock_settlement");
        execute("TRUNCATE award_stock_bucket");
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
        return b;
    }
    private static UserActivityQuota count(String user) {
        UserActivityQuota c = new UserActivityQuota();
        c.setuId(user); c.setActivityId(1L); c.setTotalCount(10);
        return c;
    }

    @Test public void existingUsersMustNotBlockOnPrimarySupremum() throws Exception {
        execute("INSERT INTO user_activity_quota(u_id,activity_id,total_count,left_count) VALUES ('userA',1,10,10),('userB',1,10,10)");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Connection held = datasource.getConnection()) {
            held.setAutoCommit(false);
            // 保持真实无变化 upsert 的 supremum 锁，模拟尚未完成的参与事务。
            try (Statement s = held.createStatement()) {
                s.executeUpdate("INSERT INTO user_activity_quota(u_id,activity_id,total_count,left_count) VALUES ('userA',1,10,10) ON DUPLICATE KEY UPDATE u_id=u_id");
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
        assertEquals(9, number("SELECT left_count FROM user_activity_quota WHERE u_id='userB'"));
    }

    @Test public void firstParticipationAndExhaustionKeepQuotaSemantics() throws Exception {
        ActivityBillVO b = bill("newUser", 2, 1);
        assertEquals(Constants.ResponseCode.SUCCESS, takes.takeParticipation(b, new java.util.Date(), IDS.incrementAndGet(), "first"));
        assertEquals(Constants.ResponseCode.NO_UPDATE, takes.takeParticipation(b, new java.util.Date(), IDS.incrementAndGet(), "second"));
        assertEquals(0, number("SELECT left_count FROM user_activity_quota"));
        assertEquals(1, number("SELECT COUNT(*) FROM draw_participation"));
    }

    @Test public void duplicateRequestRollsBackQuota() throws Exception {
        ActivityBillVO b = bill("replayUser", 3, 10);
        long take = IDS.incrementAndGet();
        assertEquals(Constants.ResponseCode.SUCCESS, takes.takeParticipation(b, new java.util.Date(), take, "same"));
        assertEquals(Constants.ResponseCode.SUCCESS, takes.consumeTake("replayUser", 3L, take));
        assertEquals(Constants.ResponseCode.INDEX_DUP, takes.takeParticipation(b, new java.util.Date(), IDS.incrementAndGet(), "same"));
        assertEquals(9, number("SELECT left_count FROM user_activity_quota"));
        assertEquals(1, number("SELECT COUNT(*) FROM draw_participation"));
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
        assertEquals(9, number("SELECT left_count FROM user_activity_quota"));
        assertEquals(1, number("SELECT COUNT(*) FROM draw_participation"));
    }

    @Test public void settlementStillAtomicAndIdempotent() throws Exception {
        execute("INSERT INTO activity_prize(activity_id,award_id,award_count,award_surplus_count,award_rate) VALUES (5,'A',1,1,1)");
        AwardStockSettlementVO first = settlement(1L);
        assertTrue(strategies.settleAwardStock(first));
        assertTrue(strategies.settleAwardStock(first));
        assertFalse(strategies.settleAwardStock(settlement(2L)));
        assertEquals(0, number("SELECT award_surplus_count FROM activity_prize"));
        assertEquals(1, number("SELECT COUNT(*) FROM award_stock_settlement"));
    }
    @Test public void bucketsHandleEmptyStartingBucketDuplicateDeliveryAndRollback() throws Exception {
        execute("INSERT INTO activity_prize(activity_id,award_id,award_count,award_surplus_count,award_rate,stock_bucket_count) VALUES (5,'A',3,3,1,8)");
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) values.append(',');
            int left = i < 3 ? 1 : 0;
            values.append("(5,'A',").append(i).append(',').append(left).append(',').append(left).append(')');
        }
        execute("INSERT INTO award_stock_bucket(activity_id,award_id,bucket_id,initial_count,remaining_count) VALUES " + values);
        for (long order = 1; order <= 3; order++) {
            assertTrue("all available stock must remain reachable", strategies.settleAwardStock(settlement(order)));
            assertTrue("duplicate MQ delivery should not deduct twice", strategies.settleAwardStock(settlement(order)));
        }
        assertFalse("fourth settlement must fail and rollback ledger", strategies.settleAwardStock(settlement(4L)));
        assertEquals(3, number("SELECT COUNT(*) FROM award_stock_settlement"));
        assertEquals(0, number("SELECT SUM(remaining_count) FROM award_stock_bucket"));
        // 分桶模式下父记录仅保留创建时的基数；真实读模型使用桶的 SUM。
        assertEquals(3, number("SELECT award_surplus_count FROM activity_prize"));
    }

    @Test public void concurrentBucketDepletionNeverOversells() throws Exception {
        execute("INSERT INTO activity_prize(activity_id,award_id,award_count,award_surplus_count,award_rate,stock_bucket_count) VALUES (5,'A',8,8,1,8)");
        StringBuilder rows = new StringBuilder();
        for (int bucket = 0; bucket < 8; bucket++) {
            if (bucket > 0) rows.append(',');
            rows.append("(5,'A',").append(bucket).append(",1,1)");
        }
        execute("INSERT INTO award_stock_bucket(activity_id,award_id,bucket_id,initial_count,remaining_count) VALUES " + rows);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (long order = 101; order <= 132; order++) {
                final long id = order;
                results.add(workers.submit(() -> {
                    go.await();
                    for (int attempt = 0; attempt < 6; attempt++) {
                        try {
                            return strategies.settleAwardStock(settlement(id));
                        } catch (org.springframework.dao.TransientDataAccessException retriable) {
                            // 模拟 RocketMQ 重新投递同一订单。瞬态死锁不能导致重复账本。
                            Thread.sleep((attempt + 1) * 20L);
                        }
                    }
                    throw new IllegalStateException("bucket retry budget exhausted for " + id);
                }));
            }
            go.countDown();
            int admitted = 0;
            for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) admitted++;
            assertEquals("cannot allocate more than available stock", 8, admitted);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals(8, number("SELECT COUNT(*) FROM award_stock_settlement"));
        assertEquals(0, number("SELECT SUM(remaining_count) FROM award_stock_bucket"));
    }


    @Test public void invalidBucketLayoutMustBlockWarmupBeforeRedisIsTouched() throws Exception {
        execute("INSERT INTO activity_prize(activity_id,award_id,award_count,award_surplus_count,award_rate,stock_bucket_count) VALUES (5,'A',8,8,1,8)");
        execute("INSERT INTO award_stock_bucket(activity_id,award_id,bucket_id,initial_count,remaining_count)"
                + " VALUES (5,'A',0,3,3),(5,'A',1,3,3)");
        IActivityPrizeDao dao = session.getMapper(IActivityPrizeDao.class);
        assertEquals(1, dao.countInvalidBucketLayouts(5L));
        ActivityRepository activity = new ActivityRepository();
        ReflectionTestUtils.setField(activity, "activityPrizeDao", dao);
        // 未注入 RedisUtil：缺桶必须在任何 Redis 调用之前抛出。
        try {
            ReflectionTestUtils.invokeMethod(activity, "warmupAwardStock", 5L);
            fail("READY activity must never warmup with missing buckets");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("layout invalid"));
        }
        execute("INSERT INTO award_stock_bucket(activity_id,award_id,bucket_id,initial_count,remaining_count)"
                + " VALUES (5,'A',2,1,1),(5,'A',3,1,1),(5,'A',4,1,1),(5,'A',5,1,1),"
                + " (5,'A',6,0,0),(5,'A',7,0,0)");
        // 桶数足够，但初始库存总和 10 != 8，一样禁止预热。
        assertEquals(1, dao.countInvalidBucketLayouts(5L));
        execute("UPDATE award_stock_bucket SET initial_count=0,remaining_count=0"
                + " WHERE activity_id=5 AND award_id='A' AND bucket_id IN (4,5)");
        assertEquals(0, dao.countInvalidBucketLayouts(5L));
        // 8 个桶都存在，但编号从 0..7 被错误替换成 0..6、8，也必须阻止启动。
        execute("UPDATE award_stock_bucket SET bucket_id=8 WHERE activity_id=5 AND award_id='A' AND bucket_id=7");
        assertEquals(1, dao.countInvalidBucketLayouts(5L));
        execute("UPDATE award_stock_bucket SET bucket_id=7 WHERE activity_id=5 AND award_id='A' AND bucket_id=8");
        assertEquals(0, dao.countInvalidBucketLayouts(5L));
        assertEquals(8, dao.queryActivityPrizeList(5L).get(0).getAwardSurplusCount().intValue());
    }

    @Test public void parentPrizeAndBucketsRollbackTogether() throws Exception {
        ActivityRepository activity = new ActivityRepository();
        ReflectionTestUtils.setField(activity, "activityPrizeDao", session.getMapper(IActivityPrizeDao.class));
        ReflectionTestUtils.setField(activity, "awardStockBucketDao", session.getMapper(IAwardStockBucketDao.class));
        ReflectionTestUtils.setField(activity, "stockBucketCount", 8);
        ReflectionTestUtils.setField(activity, "twoLevelCacheService", mock(TwoLevelCacheService.class));
        ActivityPrizeVO prize = new ActivityPrizeVO();
        prize.setActivityId(5L); prize.setAwardId("A");
        prize.setAwardCount(19); prize.setAwardSurplusCount(19);
        prize.setAwardRate(java.math.BigDecimal.ONE);
        try {
            transaction.execute(status -> {
                activity.addActivityPrizeList(Collections.singletonList(prize));
                throw new IllegalStateException("simulate failure after both writes");
            });
            fail("must propagate failure");
        } catch (IllegalStateException expected) {
            assertEquals("simulate failure after both writes", expected.getMessage());
        }
        assertEquals(0, number("SELECT COUNT(*) FROM activity_prize"));
        assertEquals(0, number("SELECT COUNT(*) FROM award_stock_bucket"));
    }


    @Test public void existingSingleRowAwardAndNewBucketedAwardCanCoexist() throws Exception {
        execute("INSERT INTO activity_prize(activity_id,award_id,award_count,award_surplus_count,award_rate,stock_bucket_count)"
                + " VALUES (5,'A',2,2,0.5,0),(5,'B',2,2,0.5,8)");
        StringBuilder buckets = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) buckets.append(',');
            int left = i < 2 ? 1 : 0;
            buckets.append("(5,'B',").append(i).append(',').append(left).append(',').append(left).append(')');
        }
        execute("INSERT INTO award_stock_bucket(activity_id,award_id,bucket_id,initial_count,remaining_count) VALUES " + buckets);
        assertEquals(0, session.getMapper(IActivityPrizeDao.class).countInvalidBucketLayouts(5L));
        AwardStockSettlementVO oldAward = settlement(201L);
        AwardStockSettlementVO newAward = settlement(202L);
        newAward.setAwardId("B");
        assertTrue(strategies.settleAwardStock(oldAward));
        assertTrue(strategies.settleAwardStock(newAward));
        assertTrue(strategies.settleAwardStock(newAward));
        assertEquals(1, number("SELECT award_surplus_count FROM activity_prize WHERE award_id='A'"));
        assertEquals(2, number("SELECT award_surplus_count FROM activity_prize WHERE award_id='B'"));
        assertEquals(1, number("SELECT SUM(remaining_count) FROM award_stock_bucket WHERE award_id='B'"));
        assertEquals(2, number("SELECT COUNT(*) FROM award_stock_settlement"));
        java.util.Map<String,Integer> stock = new java.util.HashMap<>();
        for (com.anontokyo.lottery.infrastructure.po.ActivityPrize p :
                session.getMapper(IActivityPrizeDao.class).queryActivityPrizeList(5L)) {
            stock.put(p.getAwardId(), p.getAwardSurplusCount());
        }
        assertEquals(Integer.valueOf(1), stock.get("A"));
        assertEquals(Integer.valueOf(1), stock.get("B"));
    }

    private static AwardStockSettlementVO settlement(long order) {
        AwardStockSettlementVO vo = new AwardStockSettlementVO();
        vo.setOrderId(order); vo.setuId("settlementUser"); vo.setActivityId(5L); vo.setAwardId("A");
        return vo;
    }

    @Test public void totalStockMustPersistThroughRealCreationMapper() throws Exception {
        ActivityRepository activities = new ActivityRepository();
        ReflectionTestUtils.setField(activities, "activityPrizeDao", session.getMapper(IActivityPrizeDao.class));
        ReflectionTestUtils.setField(activities, "twoLevelCacheService", mock(TwoLevelCacheService.class));
        ActivityPrizeVO detail = new ActivityPrizeVO();
        detail.setActivityId(6L); detail.setAwardId("total"); detail.setAwardCount(37); detail.setAwardSurplusCount(37);
        detail.setAwardRate(java.math.BigDecimal.ONE);
        activities.addActivityPrizeList(Collections.singletonList(detail));
        // IS NULL 断言防止 JDBC getLong 将 NULL 隐式视为零，掩盖字段映射缺陷。
        assertEquals(0, number("SELECT COUNT(*) FROM activity_prize WHERE award_count IS NULL"));
        assertEquals(37, number("SELECT award_count FROM activity_prize"));
        assertEquals(37, number("SELECT award_surplus_count FROM activity_prize"));
    }

}
