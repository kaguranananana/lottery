package com.anontokyo.lottery.test.manual;

import cn.bugstack.middleware.db.router.DBContextHolder;
import cn.bugstack.middleware.db.router.strategy.IDBRouterStrategy;
import com.anontokyo.lottery.LotteryApplication;
import com.anontokyo.lottery.application.worker.LotteryXxlJob;
import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
import com.anontokyo.lottery.application.mq.consumer.LotteryStrategyAwardStockListener;
import com.alibaba.fastjson.JSON;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.aggregates.ActivityConfigRich;
import com.anontokyo.lottery.domain.activity.model.req.*;
import com.anontokyo.lottery.domain.activity.model.res.PartakeResult;
import com.anontokyo.lottery.domain.activity.model.vo.*;
import com.anontokyo.lottery.domain.activity.repository.IActivityRepository;
import com.anontokyo.lottery.domain.activity.service.deploy.IActivityDeploy;
import com.anontokyo.lottery.domain.activity.service.lifecycle.ActivityLifecycleService;
import com.anontokyo.lottery.domain.activity.service.partake.IActivityPartake;
import com.anontokyo.lottery.infrastructure.dao.IActivityDao;
import com.anontokyo.lottery.infrastructure.dao.IUserStrategyExportDao;
import com.anontokyo.lottery.infrastructure.po.UserStrategyExport;
import com.anontokyo.lottery.interfaces.web.dto.DrawRequest;
import com.anontokyo.lottery.interfaces.web.dto.DrawResponse;
import com.xxl.job.core.context.XxlJobContext;
import org.junit.*;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** 真实 HTTP → Redis 预扣 → 分片事务 → Outbox → Kafka → 账本 → 本地模拟发奖，不使用中间件 Mock。 */
@RunWith(SpringRunner.class)
@ActiveProfiles("mq-e2e")
@SpringBootTest(classes = LotteryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class FinalBusinessE2eManualIT {
    // 策略号沿用参与基线的 INT 约束；测试活动号也在相同范围内独立递增。
    private static final AtomicLong IDS = new AtomicLong(600000000L + System.currentTimeMillis() % 100000000L);
    @Autowired private IActivityDeploy deploy;
    @Autowired private IActivityDao activityDao;
    @Autowired private IActivityRepository activities;
    @Autowired private ActivityLifecycleService lifecycle;
    @Autowired private IActivityPartake partake;
    @Autowired private LotteryXxlJob job;
    @Autowired private TestRestTemplate http;
    @Autowired private StringRedisTemplate redis;
    @Autowired private IDBRouterStrategy router;
    @Autowired private IUserStrategyExportDao orders;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private IOrderRepository orderRepository;
    @Autowired private KafkaProducer producer;
    @Autowired private LotteryStrategyAwardStockListener stockListener;
    @Value("${lottery.kafka.consumer.strategy-award-stock-group-id}") private String stockGroup;

    @BeforeClass
    public static void schema() throws Exception {
        // 复用已确认的专用 compose fixture，并真实执行交付的 V3 两库/八表迁移。
        MqMiddlewareE2eManualIT.createSchema();
        Process process = new ProcessBuilder("docker", "exec", "-i", "lottery-mq-e2e-mysql-1",
                "mysql", "-uroot", "-proot").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        process.getOutputStream().write(Files.readAllBytes(Paths.get("../docs/sql/V3__draw_request_ownership.sql")));
        process.getOutputStream().close();
        assertTrue("V3 迁移超时", process.waitFor(30, TimeUnit.SECONDS));
        assertEquals("V3 迁移失败", 0, process.exitValue());
    }

    @Before
    public void context() throws Exception {
        XxlJobContext.setXxlJobContext(new XxlJobContext(1, "1,2", null, 0, 1));
        listeners.start();
        await("真实 Kafka 分区分配", () -> listeners.getListenerContainers().stream()
                .allMatch(c -> c.getAssignedPartitions() != null && !c.getAssignedPartitions().isEmpty()));
    }

    @After
    public void clearContext() {
        XxlJobContext.setXxlJobContext(null);
    }

    @Test
    public void createdActivityHasStrategyAndInitialStock() throws Exception {
        ActivityConfigReq req = config(10, 3, -60000);
        // 调用方不预填明细所属策略和剩余库存，创建服务必须完成初始化。
        assertNull(req.getActivityConfigRich().getStrategy().getStrategyDetailList().get(0).getStrategyId());
        assertNull(req.getActivityConfigRich().getStrategy().getStrategyDetailList().get(0).getAwardSurplusCount());
        deploy.createActivity(req);
        long id = req.getActivityId();
        assertEquals(req.getActivityConfigRich().getStrategy().getStrategyId().longValue(), value("SELECT strategy_id FROM lottery.activity WHERE activity_id=?", id));
        assertEquals(10, value("SELECT stock_surplus_count FROM lottery.activity WHERE activity_id=?", id));
        assertEquals(10, value("SELECT award_surplus_count FROM lottery.strategy_detail WHERE strategy_id=?", id));
    }

    @Test
    public void scanReturnsEveryCursorAcrossMoreThanTenActivities() throws Exception {
        Set<Long> expected = new HashSet<>();
        for (int i = 0; i < 23; i++) {
            ActivityConfigReq req = config(10, 3, -60000);
            deploy.createActivity(req);
            expected.add(req.getActivityId());
        }
        Set<Long> visited = new HashSet<>();
        long cursor = 0;
        while (true) {
            List<ActivityVO> page = deploy.scanToDoActivityList(cursor);
            if (page.isEmpty()) break;
            for (ActivityVO row : page) {
                assertNotNull("分页必须返回自增 id", row.getId());
                assertTrue("游标必须严格推进", row.getId() > cursor);
                assertTrue("同一轮不得重复扫描", visited.add(row.getActivityId()));
                cursor = row.getId();
            }
        }
        assertTrue("23 条活动均须被扫描", visited.containsAll(expected));
        job.lotteryActivityStateJobHandler();
        for (Long id : expected) assertEquals(Integer.valueOf(5), activityDao.queryActivityById(id).getState());
        job.lotteryActivityStateJobHandler();
        for (Long id : expected) assertEquals("10", redis.opsForValue().get(activityKey(id)));
    }

    @Test
    public void futureActivityIsNotStartedOrWarmed() throws Exception {
        ActivityConfigReq req = config(10, 3, 60000);
        deploy.createActivity(req);
        // 直接走状态机也不能绕过开始时间检查。
        assertFalse(lifecycle.transition(req.getActivityId(), Constants.ActivityState.PASS, Constants.ActivityState.DOING));
        assertEquals(Integer.valueOf(4), activityDao.queryActivityById(req.getActivityId()).getState());
        assertFalse(redis.hasKey(activityKey(req.getActivityId())));
    }

    @Test
    public void httpPipelineReplaysRequestsAndRoutesAllEightTables() throws Exception {
        long id = createStarted(30, 3);
        Map<String, String> users = new HashMap<>();
        while (users.size() < 8) {
            String user = "http" + IDS.incrementAndGet();
            users.putIfAbsent(table(user), user);
        }
        for (String user : users.values()) {
            DrawResponse first = draw(user, id, "same-request");
            assertWin(first);
            DrawResponse replay = draw(user, id, "same-request");
            assertEquals(first.getTakeId(), replay.getTakeId());
            assertEquals(first.getOrderId(), replay.getOrderId());
            assertEquals(1, countTakes(user, id));
            assertEquals(2, leftCount(user, id));
            job.awardStockSettlementOutboxJobHandler();
            awaitComplete(user, first.getOrderId());
            assertEquals(1, value("SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE order_id=?", first.getOrderId()));
            assertEquals(1, value("SELECT COUNT(*) FROM " + table(user) + " WHERE order_id=?", first.getOrderId()));
        }
        assertEquals(22, value("SELECT award_surplus_count FROM lottery.strategy_detail WHERE strategy_id=?", id));
        assertEquals("22", redis.opsForValue().get(awardKey(id)));
        assertEquals("22", redis.opsForValue().get(activityKey(id)));
    }

    @Test
    public void unfinishedRequestOwnershipRecoversThroughHttp() throws Exception {
        long id = createStarted(10, 3);
        String user = "resume" + IDS.incrementAndGet();
        // 在真实参与事务提交后中断，尚未执行抽奖与下单，保留原请求归属。
        PartakeResult pending = partake.doPartake(new PartakeReq(user, id, "request-A"));
        assertEquals(Constants.ResponseCode.SUCCESS.getCode(), pending.getCode());
        DrawResponse rejected = draw(user, id, "request-B");
        assertEquals(Constants.ResponseCode.UNFINISHED_DRAW.getCode(), rejected.getCode());
        assertEquals("request-A", rejected.getPendingRequestId());
        DrawResponse resumed = draw(user, id, "request-A");
        assertWin(resumed);
        assertEquals(pending.getTakeId(), resumed.getTakeId());
        assertEquals(1, countTakes(user, id));
        assertEquals(2, leftCount(user, id));
        job.awardStockSettlementOutboxJobHandler();
        awaitComplete(user, resumed.getOrderId());
    }

    @Test
    public void competingHttpRequestsCannotOverdrawQuota() throws Exception {
        long id = createStarted(50, 3);
        String user = "race" + IDS.incrementAndGet();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DrawResponse>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                final String request = "compete-" + i;
                futures.add(pool.submit(() -> { start.await(); return draw(user, id, request); }));
            }
            start.countDown();
            List<DrawResponse> won = new ArrayList<>();
            for (Future<DrawResponse> future : futures) {
                DrawResponse result = future.get(30, TimeUnit.SECONDS);
                if (result.isWin()) won.add(result);
                else assertTrue("竞争只允许明确的锁/次数业务结果：" + result.getCode(),
                        Arrays.asList(Constants.ResponseCode.DRAW_IN_PROGRESS.getCode(), Constants.ResponseCode.NO_UPDATE.getCode()).contains(result.getCode()));
            }
            assertFalse(won.isEmpty());
            assertTrue(won.size() <= 3);
            assertEquals(won.size(), countTakes(user, id));
            assertEquals(3 - won.size(), leftCount(user, id));
            assertEquals(String.valueOf(50 - won.size()), redis.opsForValue().get(activityKey(id)));
            job.awardStockSettlementOutboxJobHandler();
            for (DrawResponse result : won) awaitComplete(user, result.getOrderId());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void exhaustedActivityDoesNotCreateExtraTakeOrOrder() throws Exception {
        long id = createStarted(1, 3);
        String user = "empty" + IDS.incrementAndGet();
        DrawResponse first = draw(user, id, "first");
        assertWin(first);
        DrawResponse second = draw(user, id, "second");
        assertEquals(Constants.ResponseCode.OUT_OF_STOCK.getCode(), second.getCode());
        assertEquals(1, countTakes(user, id));
        assertEquals(2, leftCount(user, id));
        job.awardStockSettlementOutboxJobHandler();
        awaitComplete(user, first.getOrderId());
        assertEquals(0, value("SELECT award_surplus_count FROM lottery.strategy_detail WHERE strategy_id=?", id));
        assertEquals("0", redis.opsForValue().get(awardKey(id)));
    }

    @Test
    public void redisOutagePreventsStartAndRecoversAfterRestart() throws Exception {
        ActivityConfigReq req = config(10, 3, -60000);
        deploy.createActivity(req);
        try {
            // 真实停止专用 Redis，预热必须失败且数据库仍为 PASS。
            MqMiddlewareE2eManualIT.compose("stop", "redis");
            assertThrows(RuntimeException.class, () -> lifecycle.transition(req.getActivityId(),
                    Constants.ActivityState.PASS, Constants.ActivityState.DOING));
            assertEquals(Integer.valueOf(4), activityDao.queryActivityById(req.getActivityId()).getState());
        } finally {
            MqMiddlewareE2eManualIT.compose("up", "-d", "--wait", "redis");
        }
        assertTrue(lifecycle.transition(req.getActivityId(), Constants.ActivityState.PASS, Constants.ActivityState.DOING));
        assertEquals("10", redis.opsForValue().get(activityKey(req.getActivityId())));
        assertEquals("10", redis.opsForValue().get(awardKey(req.getActivityId())));
    }

    @Test
    public void partialWarmupFailureKeepsPassAndSetNxPreservesExistingStock() throws Exception {
        ActivityConfigReq req = config(10, 3, -60000);
        deploy.createActivity(req);
        long id = req.getActivityId();
        try {
            // 真实 Redis ACL 只允许写活动 Key，奖品 SETNX 失败，复现第二步预热故障。
            redisCommand("ACL", "SETUSER", "default", "resetkeys", "%R~*", "%W~" + activityKey(id));
            assertThrows(RuntimeException.class, () -> lifecycle.transition(id, Constants.ActivityState.PASS, Constants.ActivityState.DOING));
            assertEquals(Integer.valueOf(4), activityDao.queryActivityById(id).getState());
            assertEquals("10", redis.opsForValue().get(activityKey(id)));
            assertFalse(redis.hasKey(awardKey(id)));
        } finally {
            redisCommand("ACL", "SETUSER", "default", "resetkeys", "~*");
        }
        // 已存在 Key 的实时值必须保留；恢复启动只补上缺失奖品 Key。
        redis.opsForValue().set(activityKey(id), "7");
        assertTrue(lifecycle.transition(id, Constants.ActivityState.PASS, Constants.ActivityState.DOING));
        assertEquals("7", redis.opsForValue().get(activityKey(id)));
        assertEquals("10", redis.opsForValue().get(awardKey(id)));
        redis.opsForValue().set(awardKey(id), "8");
        assertFalse(lifecycle.transition(id, Constants.ActivityState.PASS, Constants.ActivityState.DOING));
        assertEquals("8", redis.opsForValue().get(awardKey(id)));
        // 运行期缺失奖品库存不能由重复启动或直接预热复活。
        redis.delete(awardKey(id));
        assertFalse(lifecycle.transition(id, Constants.ActivityState.PASS, Constants.ActivityState.DOING));
        assertThrows(IllegalStateException.class, () -> activities.warmupStockForActivity(id));
        assertFalse(redis.hasKey(awardKey(id)));
    }

    @Test
    public void concurrentStartsAreSerializedAndDoNotResetStock() throws Exception {
        ActivityConfigReq req = config(10, 3, -60000);
        deploy.createActivity(req);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> {
                start.await();
                return lifecycle.transition(req.getActivityId(), Constants.ActivityState.PASS, Constants.ActivityState.DOING);
            }));
            start.countDown();
            int started = 0;
            for (Future<Boolean> future : futures) if (future.get(30, TimeUnit.SECONDS)) started++;
            assertEquals(1, started);
            redis.opsForValue().decrement(awardKey(req.getActivityId()));
            job.lotteryActivityStateJobHandler();
            assertEquals("9", redis.opsForValue().get(awardKey(req.getActivityId())));
        } finally { pool.shutdownNow(); }
    }

    @Test
    public void createFailureRollsBackActivityStrategyAndAwards() throws Exception {
        ActivityConfigReq req = config(10, 3, -60000);
        // 后续策略插入唯一键失败：先插入的活动和奖品也必须回滚。
        update("INSERT INTO lottery.strategy(strategy_id,strategy_mode,grant_type) VALUES (?,1,1)", req.getActivityId());
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> deploy.createActivity(req));
        assertEquals(0, value("SELECT COUNT(*) FROM lottery.activity WHERE activity_id=?", req.getActivityId()));
        assertEquals(0, value("SELECT COUNT(*) FROM lottery.award WHERE award_id=?", "p" + req.getActivityId()));
        assertEquals(0, value("SELECT COUNT(*) FROM lottery.strategy_detail WHERE strategy_id=?", req.getActivityId()));
    }

    @Test
    public void stockGapIsDurableBlockedAndReconciledOrderCanFinish() throws Exception {
        long id = createStarted(10, 3);
        String user = "gap" + IDS.incrementAndGet();
        DrawResponse result = draw(user, id, "gap-request");
        assertWin(result);
        // 已预扣且有持久订单，但全局库存被置为零，模拟真实库存分歧。
        update("UPDATE lottery.strategy_detail SET award_surplus_count=0 WHERE strategy_id=?", id);
        job.awardStockSettlementOutboxJobHandler();
        await("库存分歧持久化为 BLOCKED", () -> order(user, result.getOrderId()).getStockMqState() == 4);
        assertEquals(0, value("SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE order_id=?", result.getOrderId()));
        assertEquals(Integer.valueOf(0), order(user, result.getOrderId()).getGrantState());
        // 真实 broker 重复投递也可以 ACK，但不能产生账本或发奖。
        AwardStockSettlementVO settlement = settlement(user, id, result.getOrderId());
        RecordMetadata record = producer.sendStrategyAwardStock(settlement).get(30, TimeUnit.SECONDS).getRecordMetadata();
        awaitStockOffset(record);
        for (int i = 0; i < 3; i++) job.awardStockSettlementOutboxJobHandler();
        assertFalse(orderRepository.scanAwardStockMqStateByShard(db(user), tb(user), 200).stream()
                .anyMatch(row -> row.getOrderId().equals(result.getOrderId())));
        // 迟到发送成功/失败不可解除待对账状态。
        orderRepository.updateStockMqState(user, result.getOrderId(), 3);
        orderRepository.updateStockMqState(user, result.getOrderId(), 2);
        assertEquals(Integer.valueOf(4), order(user, result.getOrderId()).getStockMqState());
        // 人工确认并修复全局库存，再在正确用户分片按订单 CAS 释放原 Outbox。
        update("UPDATE lottery.strategy_detail SET award_surplus_count=10 WHERE strategy_id=?", id);
        update("UPDATE " + table(user) + " SET stock_mq_state=0 WHERE u_id=? AND order_id=? AND stock_mq_state=4", user, result.getOrderId());
        job.awardStockSettlementOutboxJobHandler();
        awaitComplete(user, result.getOrderId());
        assertEquals(1, value("SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE order_id=?", result.getOrderId()));
        assertEquals(9, value("SELECT award_surplus_count FROM lottery.strategy_detail WHERE strategy_id=?", id));
        assertEquals("9", redis.opsForValue().get(awardKey(id)));
    }

    @Test
    public void realSqlFailureDoesNotAckOrBlockAndBrokerReplayCanSettle() throws Exception {
        long id = createStarted(10, 3);
        String user = "sql" + IDS.incrementAndGet();
        DrawResponse result = draw(user, id, "sql-failure");
        assertWin(result);
        AwardStockSettlementVO settlement = settlement(user, id, result.getOrderId());
        java.util.concurrent.atomic.AtomicBoolean acknowledged = new java.util.concurrent.atomic.AtomicBoolean();
        listeners.stop();
        update("RENAME TABLE lottery.award_stock_settlement TO lottery.award_stock_settlement_unavailable");
        try {
            // 直接调用生产消费者验证 ACK 边界，但 SQL、事务、分片均真实执行。
            assertThrows(org.springframework.dao.DataAccessException.class, () -> stockListener.onMessage(
                    new ConsumerRecord<>("lottery_strategy_award_stock", 0, 0, String.valueOf(result.getOrderId()), JSON.toJSONString(settlement)),
                    () -> acknowledged.set(true), "lottery_strategy_award_stock"));
            assertFalse("SQL 故障不能 ACK", acknowledged.get());
            assertEquals(Integer.valueOf(0), order(user, result.getOrderId()).getStockMqState());
            assertEquals(10, value("SELECT award_surplus_count FROM lottery.strategy_detail WHERE strategy_id=?", id));
        } finally {
            // 仅恢复专用测试实例的表，失败不可留下已改名的 fixture。
            update("RENAME TABLE lottery.award_stock_settlement_unavailable TO lottery.award_stock_settlement");
            listeners.start();
        }
        assertEquals(0, value("SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE order_id=?", result.getOrderId()));
        // 原订单仍处于可补偿 INIT，由真实 Kafka 重新投递完成，而非测试直接结算。
        job.awardStockSettlementOutboxJobHandler();
        awaitComplete(user, result.getOrderId());
        assertEquals(1, value("SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE order_id=?", result.getOrderId()));
        assertEquals(9, value("SELECT award_surplus_count FROM lottery.strategy_detail WHERE strategy_id=?", id));
    }

    @Test
    public void expiredPassedAndRunningActivitiesCloseWithoutWarmup() throws Exception {
        ActivityConfigReq passed = config(10, 3, -120000);
        deploy.createActivity(passed);
        update("UPDATE lottery.activity SET end_date_time=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE activity_id=?", passed.getActivityId());
        long running = createStarted(10, 3);
        update("UPDATE lottery.activity SET end_date_time=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE activity_id=?", running);
        job.lotteryActivityStateJobHandler();
        assertEquals(Integer.valueOf(7), activityDao.queryActivityById(passed.getActivityId()).getState());
        assertFalse(redis.hasKey(activityKey(passed.getActivityId())));
        assertEquals(Integer.valueOf(7), activityDao.queryActivityById(running).getState());
        assertEquals("10", redis.opsForValue().get(activityKey(running)));
    }

    private AwardStockSettlementVO settlement(String user, long id, long orderId) {
        AwardStockSettlementVO result = new AwardStockSettlementVO();
        result.setuId(user);
        result.setOrderId(orderId);
        result.setStrategyId(id);
        result.setAwardId("p" + id);
        return result;
    }

    private int db(String user) {
        try { router.doRouter(user); return Integer.parseInt(DBContextHolder.getDBKey()); }
        finally { router.clear(); }
    }

    private int tb(String user) {
        try { router.doRouter(user); return Integer.parseInt(DBContextHolder.getTBKey()); }
        finally { router.clear(); }
    }

    private void awaitStockOffset(RecordMetadata record) throws Exception {
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        try (AdminClient admin = AdminClient.create(Collections.singletonMap("bootstrap.servers", "127.0.0.1:19092"))) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(stockGroup)
                        .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
                if (offsets.containsKey(partition) && offsets.get(partition).offset() > record.offset()) return;
                Thread.sleep(50);
            }
            fail("真实消费者尚未提交库存消息位点：" + record.offset());
        }
    }

    private void redisCommand(String... args) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList("docker", "exec", "lottery-mq-e2e-redis-1", "redis-cli"));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String response = new String(org.springframework.util.StreamUtils.copyToByteArray(process.getInputStream()), java.nio.charset.StandardCharsets.UTF_8).trim();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        assertEquals("Redis ACL 设置必须成功", "OK", response);
    }

    private ActivityConfigReq config(int stock, int quota, long beginOffset) {
        long id = IDS.incrementAndGet();
        ActivityVO activity = new ActivityVO();
        activity.setActivityId(id);
        activity.setActivityName("真实链路回归");
        activity.setActivityDesc("专用 E2E fixture");
        activity.setBeginDateTime(new java.util.Date(System.currentTimeMillis() + beginOffset));
        activity.setEndDateTime(new java.util.Date(System.currentTimeMillis() + 3600000));
        activity.setStockCount(stock);
        activity.setTakeCount(quota);
        activity.setState(4);
        activity.setCreator("e2e");
        StrategyVO strategy = new StrategyVO();
        strategy.setStrategyId(id);
        strategy.setStrategyMode(1);
        strategy.setGrantType(1);
        StrategyDetailVO detail = new StrategyDetailVO();
        detail.setAwardId("p" + id);
        detail.setAwardName("本地模拟券");
        detail.setAwardCount(stock);
        // 100% 中奖仅为稳定 fixture，不替换真实抽样实现。
        detail.setAwardRate(new BigDecimal("1.00"));
        strategy.setStrategyDetailList(Collections.singletonList(detail));
        AwardVO award = new AwardVO();
        award.setAwardId(detail.getAwardId());
        award.setAwardType(3);
        award.setAwardName(detail.getAwardName());
        award.setAwardContent("项目已有模拟发奖渠道");
        return new ActivityConfigReq(id, new ActivityConfigRich(activity, strategy, Collections.singletonList(award)));
    }

    private long createStarted(int stock, int quota) throws Exception {
        ActivityConfigReq req = config(stock, quota, -60000);
        deploy.createActivity(req);
        assertTrue(lifecycle.transition(req.getActivityId(), Constants.ActivityState.PASS, Constants.ActivityState.DOING));
        return req.getActivityId();
    }

    private DrawResponse draw(String user, long id, String requestId) {
        DrawRequest request = new DrawRequest();
        request.setUserId(user);
        request.setActivityId(id);
        request.setRequestId(requestId);
        return http.postForObject("/api/v1/draw", request, DrawResponse.class);
    }

    private void assertWin(DrawResponse result) {
        assertNotNull(result);
        assertEquals(result.getInfo(), Constants.ResponseCode.SUCCESS.getCode(), result.getCode());
        assertTrue(result.isWin());
        assertNotNull(result.getOrderId());
    }

    private UserStrategyExport order(String user, long orderId) {
        UserStrategyExport query = new UserStrategyExport();
        query.setuId(user);
        query.setUuid(String.valueOf(orderId));
        return orders.queryUserStrategyExportByUuid(query);
    }

    private void awaitComplete(String user, long orderId) throws Exception {
        await("结算/发奖完成 " + orderId, () -> {
            UserStrategyExport row = order(user, orderId);
            return row.getStockMqState() == 1 && row.getMqState() == 1 && row.getGrantState() == 1;
        });
    }

    private String table(String user) {
        try {
            router.doRouter(user);
            return "lottery_" + DBContextHolder.getDBKey() + ".user_strategy_export_" + DBContextHolder.getTBKey();
        } finally { router.clear(); }
    }

    private String userSchema(String user) {
        try {
            router.doRouter(user);
            return "lottery_" + DBContextHolder.getDBKey();
        } finally { router.clear(); }
    }

    private long countTakes(String user, long id) throws Exception {
        return value("SELECT COUNT(*) FROM " + userSchema(user) + ".user_take_activity WHERE u_id=? AND activity_id=?", user, id);
    }

    private long leftCount(String user, long id) throws Exception {
        return value("SELECT left_count FROM " + userSchema(user) + ".user_take_activity_count WHERE u_id=? AND activity_id=?", user, id);
    }

    private String activityKey(long id) { return Constants.RedisKey.KEY_LOTTERY_ACTIVITY_STOCK_COUNT(id); }
    private String awardKey(long id) { return Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_COUNT(id, "p" + id); }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://127.0.0.1:13309/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", "root", "root");
    }

    private static long value(String sql, Object... args) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                long value = result.getLong(1);
                assertFalse("查询字段不能是 NULL：" + sql, result.wasNull());
                return value;
            }
        }
    }

    private static void update(String sql, Object... args) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            statement.executeUpdate();
        }
    }

    private static void await(String condition, BooleanSupplier probe) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (probe.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("等待真实异步事件超时：" + condition);
    }
}
