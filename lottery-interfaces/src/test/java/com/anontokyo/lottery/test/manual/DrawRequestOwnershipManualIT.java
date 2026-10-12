package com.anontokyo.lottery.test.manual;

import cn.bugstack.middleware.db.router.DBContextHolder;
import cn.bugstack.middleware.db.router.DBRouterConfig;
import cn.bugstack.middleware.db.router.DBRouterJoinPoint;
import cn.bugstack.middleware.db.router.dynamic.DynamicDataSource;
import cn.bugstack.middleware.db.router.dynamic.DynamicMybatisPlugin;
import cn.bugstack.middleware.db.router.strategy.IDBRouterStrategy;
import cn.bugstack.middleware.db.router.strategy.impl.DBRouterStrategyHashCode;
import com.anontokyo.lottery.application.process.draw.impl.ActivityProcessImpl;
import com.anontokyo.lottery.application.process.draw.req.DrawProcessReq;
import com.anontokyo.lottery.application.process.draw.res.DrawProcessResult;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.res.StockResult;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.UserTakeActivityVO;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.domain.activity.service.partake.impl.ActivityPartakeImpl;
import com.anontokyo.lottery.domain.award.service.order.impl.AwardOrderImpl;
import com.anontokyo.lottery.domain.strategy.model.res.DrawResult;
import com.anontokyo.lottery.domain.strategy.model.vo.DrawAwardInfo;
import com.anontokyo.lottery.domain.strategy.service.draw.IDrawExec;
import com.anontokyo.lottery.domain.support.ids.IdGenerator;
import com.anontokyo.lottery.domain.support.lock.IActivityDrawProcessGuard;
import com.anontokyo.lottery.infrastructure.dao.*;
import com.anontokyo.lottery.infrastructure.repository.OrderRepository;
import com.anontokyo.lottery.infrastructure.repository.UserTakeActivityRepository;
import org.junit.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 隔离 MySQL 集成测试：真实生产 Mapper、路由切面、事务及 Repository。
 * 必须在专用空 MySQL 实例运行，会建立 lottery/lottery_01/lottery_02 和测试表。
 * Redis 未消费缓存不再参与决策；仅活动库存、抽样与短锁用 Mock，数据库不使用 Mock。
 */
public class DrawRequestOwnershipManualIT {
    private static String mysqlUrl;
    private static final AtomicLong ids = new AtomicLong(System.currentTimeMillis());
    private static IDBRouterStrategy router;
    private static UserTakeActivityRepository takes;
    private static OrderRepository orders;
    private ActivityProcessImpl process;
    private IDrawExec draw;
    private String user;
    private ActivityBillVO bill;

    @BeforeClass
    public static void database() throws Exception {
        mysqlUrl = System.getProperty("lottery.it.mysql");
        assertNotNull("请显式传入专用空 MySQL 实例 URL（末尾 /，不含数据库）", mysqlUrl);
        // 使用基线建表，不导入业务种子，不启动 Kafka 或 XXL-Job。
        String baseline = new String(Files.readAllBytes(Paths.get("../doc/sql/lottery_01.sql")), StandardCharsets.UTF_8);
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS lottery");
            for (String schema : Arrays.asList("lottery_01", "lottery_02")) {
                statement.execute("CREATE DATABASE IF NOT EXISTS " + schema);
                statement.execute("USE " + schema);
                Matcher matcher = Pattern.compile("CREATE TABLE `(user_take_activity(?:_count)?|user_strategy_export_00[0-3])` \\(.*?;", Pattern.DOTALL).matcher(baseline);
                while (matcher.find()) {
                    statement.execute(matcher.group().replace("CREATE TABLE", "CREATE TABLE IF NOT EXISTS"));
                }
                // 既有 Outbox 迁移的前置字段，本测试不调用其结算链路。
                for (int table = 0; table < 4; table++) {
                    String name = String.format("user_strategy_export_%03d", table);
                    try (ResultSet columns = connection.getMetaData().getColumns(schema, null, name, "stock_mq_state")) {
                        if (!columns.next()) {
                            statement.execute("ALTER TABLE " + name + " ADD stock_mq_state TINYINT DEFAULT 0");
                        }
                    }
                }
            }
        }
        // 直接执行交付迁移，证明实际两库与八张订单表都能升级。
        executeMigration();
        DBRouterConfig config = new DBRouterConfig(2, 4, "uId");
        router = new DBRouterStrategyHashCode(config);
        DynamicDataSource datasource = new DynamicDataSource();
        Map<Object, Object> targets = new HashMap<>();
        for (int db = 0; db <= 2; db++) {
            String schema = db == 0 ? "lottery" : String.format("lottery_%02d", db);
            targets.put(String.format("db%02d", db), new DriverManagerDataSource(mysqlUrl + schema + "?useSSL=false&allowPublicKeyRetrieval=true", "root", ""));
        }
        datasource.setTargetDataSources(targets);
        ReflectionTestUtils.setField(datasource, "defaultDataSource", "db00");
        datasource.afterPropertiesSet();
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(datasource);
        factory.setTypeAliasesPackage("com.anontokyo.lottery.infrastructure.po");
        factory.setPlugins(new DynamicMybatisPlugin());
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:mybatis/mapper/*_Mapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        DBRouterJoinPoint aspect = new DBRouterJoinPoint(config, router);
        IUserTakeActivityDao takeDao = routed(IUserTakeActivityDao.class, session.getMapper(IUserTakeActivityDao.class), aspect);
        IUserTakeActivityCountDao countDao = routed(IUserTakeActivityCountDao.class, session.getMapper(IUserTakeActivityCountDao.class), aspect);
        IUserStrategyExportDao orderDao = routed(IUserStrategyExportDao.class, session.getMapper(IUserStrategyExportDao.class), aspect);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        takes = new UserTakeActivityRepository();
        orders = new OrderRepository();
        ReflectionTestUtils.setField(takes, "userTakeActivityDao", takeDao);
        ReflectionTestUtils.setField(takes, "userTakeActivityCountDao", countDao);
        ReflectionTestUtils.setField(orders, "userTakeActivityDao", takeDao);
        ReflectionTestUtils.setField(orders, "userStrategyExportDao", orderDao);
        for (Object repository : Arrays.asList(takes, orders)) {
            ReflectionTestUtils.setField(repository, "dbRouter", router);
            ReflectionTestUtils.setField(repository, "transactionTemplate", transaction);
        }
    }

    @Before
    public void pipeline() {
        user = "it" + ids.incrementAndGet();
        bill = new ActivityBillVO();
        bill.setuId(user);
        bill.setActivityId(100001L);
        bill.setActivityName("幂等测试");
        bill.setStrategyId(10001L);
        bill.setTakeCount(10);
        bill.setState(Constants.ActivityState.DOING.getCode());
        bill.setBeginDateTime(new java.util.Date(0));
        bill.setEndDateTime(new java.util.Date(Long.MAX_VALUE));
        bill.setStockCount(100);
        bill.setStockSurplusCount(100);
        IActivityRepository activities = mock(IActivityRepository.class);
        when(activities.queryActivityBill(any())).thenReturn(bill);
        StockResult stock = new StockResult(Constants.ResponseCode.SUCCESS.getCode(), "成功", 99);
        when(activities.substractionActivityStockByRedis(anyString(), anyLong())).thenReturn(stock);
        IdGenerator generator = ids::incrementAndGet;
        ActivityPartakeImpl partake = new ActivityPartakeImpl();
        ReflectionTestUtils.setField(partake, "userTakeActivityRepository", takes);
        ReflectionTestUtils.setField(partake, "activityRepository", activities);
        // 参与支持类与实现类各持有一个活动仓储字段，手动装配须同时设置。
        ReflectionTestUtils.setField(partake, com.anontokyo.lottery.domain.activity.service.partake.ActivityPartakeSupport.class,
                "activityRepository", activities, IActivityRepository.class);
        ReflectionTestUtils.setField(partake, "idGenerator", generator);
        AwardOrderImpl awardOrder = new AwardOrderImpl();
        ReflectionTestUtils.setField(awardOrder, "orderRepository", orders);
        ReflectionTestUtils.setField(awardOrder, "idGenerator", generator);
        draw = mock(IDrawExec.class);
        when(draw.doDrawExec(any())).thenReturn(new DrawResult(user, 10001L, Constants.DrawState.SUCCESS.getCode(),
                new DrawAwardInfo("3", 3, "优惠券", "30元")));
        IActivityDrawProcessGuard guard = mock(IActivityDrawProcessGuard.class);
        // 所有线程均进入，模拟短锁过期；幂等性必须由真实数据库约束与 CAS 保证。
        when(guard.tryClaim(anyLong(), anyString(), anyString())).thenReturn(true);
        process = new ActivityProcessImpl();
        ReflectionTestUtils.setField(process, "activityDrawProcessGuard", guard);
        ReflectionTestUtils.setField(process, "userTakeActivityRepository", takes);
        ReflectionTestUtils.setField(process, "orderRepository", orders);
        ReflectionTestUtils.setField(process, "activityPartake", partake);
        ReflectionTestUtils.setField(process, "awardOrder", awardOrder);
        ReflectionTestUtils.setField(process, "drawExec", draw);
    }

    @Test
    public void firstReplayThenNewRoundAcrossAllShards() throws Exception {
        Set<String> shards = new HashSet<>();
        // 覆盖两库四分表；回放无路由注解时会查询全局库或错误分表并失败。
        String prefix = "r" + ids.incrementAndGet();
        for (int i = 0; shards.size() < 8; i++) {
            user = prefix + i;
            bill.setuId(user);
            router.doRouter(user);
            String shard = DBContextHolder.getDBKey() + DBContextHolder.getTBKey();
            router.clear();
            if (!shards.add(shard)) { continue; }
            String requestId = "A" + ids.incrementAndGet();
            DrawProcessResult a = request(requestId);
            assertEquals(Constants.ResponseCode.SUCCESS.getCode(), a.getCode());
            assertNull(takes.queryNoConsumedTakeActivityOrder(100001L, user));
            DrawProcessResult replay = request(requestId);
            assertEquals(a.getTakeId(), replay.getTakeId());
            assertEquals(a.getOrderId(), replay.getOrderId());
            assertEquals(9, leftCount());
            assertEquals(1, orderCount());
            // A 已完成后仍受请求唯一键约束，不能以第二条 take 重新扣减。
            assertEquals(Constants.ResponseCode.INDEX_DUP,
                    takes.takeParticipation(bill, new java.util.Date(), ids.incrementAndGet(), requestId));
            assertEquals(9, leftCount());
            DrawProcessResult b = request("B" + ids.incrementAndGet());
            assertEquals(Constants.ResponseCode.SUCCESS.getCode(), b.getCode());
            assertNotEquals(a.getTakeId(), b.getTakeId());
            assertEquals(8, leftCount());
        }
    }

    @Test
    public void interruptionBlocksBAndAResumesWithoutCache() throws Exception {
        when(draw.doDrawExec(any())).thenThrow(new IllegalStateException("after take"));
        try { request("A"); fail("必须暴露抽奖异常"); }
        catch (IllegalStateException expected) { assertEquals("after take", expected.getMessage()); }
        UserTakeActivityVO original = takes.queryTakeActivityByRequestId(100001L, user, "A");
        assertEquals("A", original.getRequestId());
        assertEquals(9, leftCount());
        DrawProcessResult blocked = request("B");
        assertEquals(Constants.ResponseCode.UNFINISHED_DRAW.getCode(), blocked.getCode());
        assertEquals("A", blocked.getPendingRequestId());
        assertNull(blocked.getTakeId());
        // 全程没有 Redis 缓存，恢复必须使用数据库原记录。
        doReturn(new DrawResult(user, 10001L, Constants.DrawState.FAIL.getCode())).when(draw).doDrawExec(any());
        DrawProcessResult resumed = request(blocked.getPendingRequestId());
        assertEquals(original.getTakeId(), resumed.getTakeId());
        assertEquals(Constants.ResponseCode.LOSING_DRAW.getCode(), request("A").getCode());
        assertEquals(9, leftCount());
        assertNotEquals(original.getTakeId(), request("B").getTakeId());
        assertEquals(8, leftCount());
    }

    @Test
    public void concurrentSameRequestCreatesOneTakeAndOneOrder() throws Exception {
        List<DrawProcessResult> results = concurrent(8, index -> request("A"));
        for (DrawProcessResult result : results) {
            assertEquals(Constants.ResponseCode.SUCCESS.getCode(), result.getCode());
            assertEquals(results.get(0).getTakeId(), result.getTakeId());
            assertEquals(results.get(0).getOrderId(), result.getOrderId());
        }
        assertEquals(9, leftCount());
        assertEquals(1, takeCount());
        assertEquals(1, orderCount());
    }

    @Test
    public void concurrentDifferentRequestsRollbackLosingQuota() throws Exception {
        // 直接竞争参与事务，绕过应用短锁与读检查，验证唯一键而非 Mock 的竞争结果。
        List<Constants.ResponseCode> results = concurrent(8, index ->
                takes.takeParticipation(bill, new java.util.Date(), ids.incrementAndGet(), "request" + index));
        assertEquals(1, Collections.frequency(results, Constants.ResponseCode.SUCCESS));
        assertEquals(7, Collections.frequency(results, Constants.ResponseCode.INDEX_DUP));
        assertEquals(9, leftCount());
        assertEquals(1, takeCount());
        assertNotNull(takes.queryNoConsumedTakeActivityOrder(100001L, user).getRequestId());
    }

    @Test
    public void exhaustedQuotaDoesNotCreateTakeWithMatchedRowsDriver() throws Exception {
        bill.setTakeCount(1);
        DrawProcessResult first = request("A");
        assertEquals(Constants.ResponseCode.SUCCESS.getCode(), first.getCode());
        assertEquals(Constants.ResponseCode.NO_UPDATE.getCode(), request("B").getCode());
        assertEquals(0, leftCount());
        assertEquals(1, takeCount());
    }

    @Test
    public void realSqlFailureLeavesQuotaAndTakeUntouched() throws Exception {
        assertEquals(Constants.ResponseCode.SUCCESS,
                takes.takeParticipation(bill, new java.util.Date(), ids.incrementAndGet(), "A"));
        String schema = schema();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + schema + ".user_take_activity RENAME COLUMN request_id TO unavailable_request_id");
            try {
                request("A");
                fail("真实 SQL 查询错误必须中断");
            } catch (org.springframework.dao.DataAccessException expected) {
                assertTrue(expected.getMessage().contains("request_id"));
            } finally {
                statement.execute("ALTER TABLE " + schema + ".user_take_activity RENAME COLUMN unavailable_request_id TO request_id");
            }
        }
        assertEquals(9, leftCount());
        assertEquals(1, takeCount());
        assertEquals(Constants.TaskState.NO_USED.getCode(), takes.queryTakeActivityByRequestId(100001L, user, "A").getState());
    }

    @Test
    public void orderInsertSqlFailureRollsBackConsumptionAndAResumes() throws Exception {
        router.doRouter(user);
        String table = "lottery_" + DBContextHolder.getDBKey()
                + ".user_strategy_export_" + DBContextHolder.getTBKey();
        router.clear();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // 在订单事务已执行 take CAS 后制造真实 INSERT 错误，验证整个事务回滚。
            statement.execute("ALTER TABLE " + table + " RENAME COLUMN award_content TO unavailable_award_content");
            try {
                request("A");
                fail("订单 INSERT 失败必须传播");
            } catch (org.springframework.dao.DataAccessException expected) {
                assertTrue(expected.getMessage().contains("award_content"));
            } finally {
                statement.execute("ALTER TABLE " + table + " RENAME COLUMN unavailable_award_content TO award_content");
            }
        }
        UserTakeActivityVO unfinished = takes.queryTakeActivityByRequestId(100001L, user, "A");
        assertEquals(Constants.TaskState.NO_USED.getCode(), unfinished.getState());
        assertEquals(9, leftCount());
        assertEquals(0, orderCount());
        DrawProcessResult resumed = request("A");
        assertEquals(Constants.ResponseCode.SUCCESS.getCode(), resumed.getCode());
        assertEquals(unfinished.getTakeId(), resumed.getTakeId());
        assertEquals(9, leftCount());
        assertEquals(1, orderCount());
    }

    @Test
    public void legacyTakeWithoutRequestOwnershipCannotBeClaimedByB() throws Exception {
        assertEquals(Constants.ResponseCode.SUCCESS,
                takes.takeParticipation(bill, new java.util.Date(), ids.incrementAndGet(), null));
        DrawProcessResult result = request("B");
        assertEquals(Constants.ResponseCode.TAKE_STATE_ERROR.getCode(), result.getCode());
        assertNull(result.getPendingRequestId());
        assertNull(result.getTakeId());
        assertEquals(9, leftCount());
        assertEquals(1, takeCount());
        verify(draw, never()).doDrawExec(any());
    }

    @Test
    public void requestIdComparisonMatchesJavaBytes() throws Exception {
        DrawProcessResult upper = request("A");
        DrawProcessResult lower = request("a");
        DrawProcessResult trailing = request("a ");
        assertNotEquals(upper.getTakeId(), lower.getTakeId());
        assertNotEquals(lower.getTakeId(), trailing.getTakeId());
        assertEquals(trailing.getTakeId(), request("a ").getTakeId());
        assertEquals(7, leftCount());
        // 保留原接口 64 字符契约，即使中文在 UTF-8 中占多个字节。
        String unicode = String.join("", Collections.nCopies(64, "抽"));
        DrawProcessResult unicodeResult = request(unicode);
        assertEquals(Constants.ResponseCode.SUCCESS.getCode(), unicodeResult.getCode());
        assertEquals(unicodeResult.getTakeId(), request(unicode).getTakeId());
        assertEquals(6, leftCount());
    }

    private DrawProcessResult request(String requestId) {
        return process.doDrawProcess(new DrawProcessReq(user, 100001L, requestId));
    }

    private String schema() {
        router.doRouter(user);
        String schema = "lottery_" + DBContextHolder.getDBKey();
        router.clear();
        return schema;
    }

    private int orderCount() throws Exception {
        router.doRouter(user);
        String table = "lottery_" + DBContextHolder.getDBKey()
                + ".user_strategy_export_" + DBContextHolder.getTBKey();
        router.clear();
        return count("SELECT COUNT(*) FROM " + table + " WHERE u_id=?");
    }

    private int leftCount() throws Exception { return count("SELECT left_count FROM " + schema() + ".user_take_activity_count WHERE u_id=?"); }
    private int takeCount() throws Exception { return count("SELECT COUNT(*) FROM " + schema() + ".user_take_activity WHERE u_id=?"); }
    private int count(String sql) throws Exception {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, user);
            try (ResultSet result = statement.executeQuery()) { assertTrue(result.next()); return result.getInt(1); }
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(mysqlUrl + "?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
    }

    private static <T> T routed(Class<T> type, T mapper, DBRouterJoinPoint aspect) {
        AspectJProxyFactory proxy = new AspectJProxyFactory(mapper);
        proxy.addAspect(aspect);
        return type.cast(proxy.getProxy());
    }

    private interface ConcurrentAction<T> { T run(int index) throws Exception; }
    private static <T> List<T> concurrent(int size, ConcurrentAction<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(size);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < size; i++) {
                final int index = i;
                futures.add(pool.submit(() -> { start.await(); return action.run(index); }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            // 线程异常通过 Future 传播，不能吞异常后只检查主线程结果。
            for (Future<T> future : futures) { results.add(future.get(30, TimeUnit.SECONDS)); }
            return results;
        } finally { pool.shutdownNow(); }
    }

    private static void executeMigration() throws Exception {
        String delimiter = ";";
        StringBuilder sql = new StringBuilder();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String line : Files.readAllLines(Paths.get("../docs/sql/V3__draw_request_ownership.sql"), StandardCharsets.UTF_8)) {
                if (line.startsWith("--") || line.trim().isEmpty()) { continue; }
                if (line.startsWith("DELIMITER ")) { delimiter = line.substring(10); continue; }
                sql.append(line).append('\n');
                if (line.trim().endsWith(delimiter)) {
                    statement.execute(sql.toString().trim().substring(0, sql.toString().trim().length() - delimiter.length()));
                    sql.setLength(0);
                }
            }
        }
    }
}
