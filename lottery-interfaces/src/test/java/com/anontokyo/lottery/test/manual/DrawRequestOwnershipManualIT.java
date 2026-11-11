package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.application.process.draw.impl.ActivityProcessImpl;
import com.anontokyo.lottery.application.process.draw.req.DrawProcessReq;
import com.anontokyo.lottery.application.process.draw.res.DrawProcessResult;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityBillVO;
import com.anontokyo.lottery.domain.activity.model.vo.DrawParticipationVO;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.domain.draw.repository.IDrawConfigRepository;
import com.anontokyo.lottery.domain.activity.service.partake.impl.ActivityPartakeImpl;
import com.anontokyo.lottery.domain.award.service.order.impl.AwardOrderImpl;
import com.anontokyo.lottery.domain.draw.model.res.DrawResult;
import com.anontokyo.lottery.domain.draw.model.vo.DrawAwardInfo;
import com.anontokyo.lottery.domain.draw.service.draw.IDrawExec;
import com.anontokyo.lottery.domain.support.ids.IdGenerator;
import com.anontokyo.lottery.domain.support.lock.IActivityDrawProcessGuard;
import com.anontokyo.lottery.infrastructure.dao.*;
import com.anontokyo.lottery.infrastructure.repository.OrderRepository;
import com.anontokyo.lottery.infrastructure.repository.DrawParticipationRepository;
import org.junit.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

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
 * 隔离 MySQL 集成测试：真实生产 Mapper、事务及 Repository。
 * 必须在专用空 MySQL 实例运行，会建立 lottery 和测试表。
 * Redis 未消费缓存不再参与决策；仅活动配置、奖品抽样/预扣与短锁用 Mock，数据库不使用 Mock。
 */
public class DrawRequestOwnershipManualIT {
    private static String mysqlUrl;
    private static final AtomicLong ids = new AtomicLong(System.currentTimeMillis());
    private static DrawParticipationRepository takes;
    private static OrderRepository orders;
    private IDrawConfigRepository stockReservations;
    private ActivityProcessImpl process;
    private IDrawExec draw;
    private String user;
    private ActivityBillVO bill;

    @BeforeClass
    public static void database() throws Exception {
        mysqlUrl = System.getProperty("lottery.it.mysql");
        assertNotNull("请显式传入专用空 MySQL 实例 URL（末尾 /，不含数据库）", mysqlUrl);
        // 从当前完整 Schema 初始化，测试运行在显式指定的空 MySQL 实例。
        try (Connection connection = connect()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.FileSystemResource("../docs/sql/lottery-schema.sql"));
        }
        DriverManagerDataSource datasource = new DriverManagerDataSource(
                mysqlUrl + "lottery?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(datasource);
        factory.setTypeAliasesPackage("com.anontokyo.lottery.infrastructure.po");
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:mybatis/mapper/*_Mapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        IDrawParticipationDao takeDao = session.getMapper(IDrawParticipationDao.class);
        IUserActivityQuotaDao countDao = session.getMapper(IUserActivityQuotaDao.class);
        IDrawOrderDao orderDao = session.getMapper(IDrawOrderDao.class);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        takes = new DrawParticipationRepository();
        orders = new OrderRepository();
        ReflectionTestUtils.setField(takes, "drawParticipationDao", takeDao);
        ReflectionTestUtils.setField(takes, "userActivityQuotaDao", countDao);
        ReflectionTestUtils.setField(orders, "drawParticipationDao", takeDao);
        ReflectionTestUtils.setField(orders, "drawOrderDao", orderDao);
        for (Object repository : Arrays.asList(takes, orders)) {
            ReflectionTestUtils.setField(repository, "transactionTemplate", transaction);
        }
    }

    @Before
    public void pipeline() {
        user = "it" + ids.incrementAndGet();
        bill = new ActivityBillVO();
        bill.setuId(user);
        bill.setActivityId(100001L);

        bill.setDrawConfigId(10001L);
        bill.setTakeCount(10);
        bill.setState(Constants.ActivityState.DOING.getCode());
        bill.setBeginDateTime(new java.util.Date(0));
        bill.setEndDateTime(new java.util.Date(Long.MAX_VALUE));
        IActivityRepository activities = mock(IActivityRepository.class);
        // SQL 专项只模拟奖品预扣与回补，用户事务保持真实。
        stockReservations = mock(IDrawConfigRepository.class);
        ReflectionTestUtils.setField(orders, "drawConfigRepository", stockReservations);
        when(activities.queryActivityBill(any())).thenReturn(bill);
        IdGenerator generator = ids::incrementAndGet;
        ActivityPartakeImpl partake = new ActivityPartakeImpl();
        ReflectionTestUtils.setField(partake, "drawParticipationRepository", takes);
        ReflectionTestUtils.setField(partake, "activityRepository", activities);
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
        ReflectionTestUtils.setField(process, "drawParticipationRepository", takes);
        ReflectionTestUtils.setField(process, "orderRepository", orders);
        ReflectionTestUtils.setField(process, "activityPartake", partake);
        ReflectionTestUtils.setField(process, "awardOrder", awardOrder);
        ReflectionTestUtils.setField(process, "drawExec", draw);
    }

    @Test
    public void firstReplayThenNewRoundForEachUser() throws Exception {
        String prefix = "r" + ids.incrementAndGet();
        // 多个用户复用同一逻辑请求号，回放仍按用户和活动隔离。
        for (int i = 0; i < 8; i++) {
            user = prefix + i;
            bill.setuId(user);
            String requestId = "A" + ids.incrementAndGet();
            DrawProcessResult a = request(requestId);
            assertEquals(Constants.ResponseCode.SUCCESS.getCode(), a.getCode());
            assertNull(takes.queryUnfinishedParticipation(100001L, user));
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
        DrawParticipationVO original = takes.queryParticipationByRequestId(100001L, user, "A");
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
        assertNotNull(takes.queryUnfinishedParticipation(100001L, user).getRequestId());
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
    public void competingLastPersonalChanceCreatesExactlyOneTake() throws Exception {
        bill.setTakeCount(1);
        // 绕过短锁，让八个真实用户事务竞争最后一次机会，零更新不得再 INSERT take。
        List<Constants.ResponseCode> results = concurrent(8, index ->
                takes.takeParticipation(bill, new java.util.Date(), ids.incrementAndGet(), "last-" + index));
        assertEquals(1, Collections.frequency(results, Constants.ResponseCode.SUCCESS));
        assertEquals(7, Collections.frequency(results, Constants.ResponseCode.NO_UPDATE));
        assertEquals(0, leftCount());
        assertEquals(1, takeCount());
    }

    @Test
    public void realSqlFailureLeavesQuotaAndTakeUntouched() throws Exception {
        assertEquals(Constants.ResponseCode.SUCCESS,
                takes.takeParticipation(bill, new java.util.Date(), ids.incrementAndGet(), "A"));
        String schema = "lottery";
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + schema + ".draw_participation RENAME COLUMN request_id TO unavailable_request_id");
            try {
                request("A");
                fail("真实 SQL 查询错误必须中断");
            } catch (org.springframework.dao.DataAccessException expected) {
                assertTrue(expected.getMessage().contains("request_id"));
            } finally {
                statement.execute("ALTER TABLE " + schema + ".draw_participation RENAME COLUMN unavailable_request_id TO request_id");
            }
        }
        assertEquals(9, leftCount());
        assertEquals(1, takeCount());
        assertEquals(Constants.TaskState.NO_USED.getCode(), takes.queryParticipationByRequestId(100001L, user, "A").getState());
    }

    @Test
    public void orderInsertSqlFailureRollsBackConsumptionAndAResumes() throws Exception {
        String table = "lottery.draw_order";
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
        DrawParticipationVO unfinished = takes.queryParticipationByRequestId(100001L, user, "A");
        assertEquals(Constants.TaskState.NO_USED.getCode(), unfinished.getState());
        // 已确认回滚只回补本次预扣一次；真实 Redis 行为另由 HTTP E2E 验证。
        verify(stockReservations).recoverDrawConfigPrizeStockByRedis(10001L, "3");
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

    private int orderCount() throws Exception {
        String table = "lottery.draw_order";
        return count("SELECT COUNT(*) FROM " + table + " WHERE u_id=?");
    }

    private int leftCount() throws Exception { return count("SELECT left_count FROM " + "lottery" + ".user_activity_quota WHERE u_id=?"); }
    private int takeCount() throws Exception { return count("SELECT COUNT(*) FROM " + "lottery" + ".draw_participation WHERE u_id=?"); }
    private int count(String sql) throws Exception {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, user);
            try (ResultSet result = statement.executeQuery()) { assertTrue(result.next()); return result.getInt(1); }
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(mysqlUrl + "?useSSL=false&allowPublicKeyRetrieval=true", "root", "");
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

}
