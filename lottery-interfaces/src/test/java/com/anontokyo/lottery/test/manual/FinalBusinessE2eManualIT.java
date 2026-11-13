package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.LotteryApplication;
import com.anontokyo.lottery.application.worker.LotteryScheduledTasks;
import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
import com.anontokyo.lottery.application.mq.producer.OutboxDispatcher;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import com.anontokyo.lottery.domain.award.model.req.AwardOrderReq;
import com.anontokyo.lottery.domain.award.model.res.AwardOrderResult;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
import com.anontokyo.lottery.application.mq.consumer.StockSettlementListener;
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
import com.anontokyo.lottery.infrastructure.dao.IDrawOrderDao;
import com.anontokyo.lottery.infrastructure.po.DrawOrder;
import com.anontokyo.lottery.interfaces.web.dto.DrawRequest;
import com.anontokyo.lottery.interfaces.web.dto.DrawResponse;
import org.junit.*;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.servlet.Filter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
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

/** 真实 HTTP → Redis 预扣 → 单库事务 → Outbox → Kafka → 账本 → 本地模拟发奖，不使用中间件 Mock。 */
@RunWith(SpringRunner.class)
@ActiveProfiles("mq-e2e")
@SpringBootTest(classes = {LotteryApplication.class, FinalBusinessE2eManualIT.TestIdentity.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "lottery.draw.admission.global-rate=10000",
                "lottery.draw.admission.global-burst=1000", "lottery.draw.admission.user-burst=60"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class FinalBusinessE2eManualIT {
    // 测试活动号独立递增，避免重复运行时命中旧 Redis 库存键。
    private static final AtomicLong IDS = new AtomicLong(600000000L + System.currentTimeMillis() % 100000000L);
    @Autowired private IActivityDeploy deploy;
    @Autowired private IActivityDao activityDao;
    @Autowired private IActivityRepository activities;
    @Autowired private ActivityLifecycleService lifecycle;
    @Autowired private IActivityPartake partake;
    @Autowired private LotteryScheduledTasks job;
    @SpyBean private OutboxDispatcher outbox;
    @Autowired private TransactionTemplate transaction;
    @Autowired private IAwardOrder awardOrder;
    @Autowired private TestRestTemplate http;
    @Autowired private StringRedisTemplate redis;
    @Autowired private IDrawOrderDao orders;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private IOrderRepository orderRepository;
    @Autowired private KafkaProducer producer;
    @Autowired private KafkaTemplate<String, Object> kafka;
    @Autowired private StockSettlementListener stockListener;
    @Value("${lottery.kafka.consumer.strategy-award-stock-group-id}") private String stockGroup;

    @BeforeClass
    public static void schema() throws Exception {
        // 复用真实中间件测试栈，从当前完整 Schema 建库。
        MqMiddlewareE2eManualIT.createSchema();
    }

    @Before
    public void context() throws Exception {
        // 旧故障场景按需控制提交后调度缺失窗口，实时投递由单独用例验证。
        org.mockito.Mockito.doNothing().when(outbox).afterOrderCommit(org.mockito.ArgumentMatchers.any());
        listeners.start();
        await("真实 Kafka 分区分配", () -> listeners.getListenerContainers().stream()
                .allMatch(c -> c.getAssignedPartitions() != null && !c.getAssignedPartitions().isEmpty()));
    }

    @Test
    public void missingAwardInventoryPreservesTheUnfinishedTake() throws Exception {
        long id = createStarted(10, 3);
        String user = "missing" + IDS.incrementAndGet();
        redis.delete(awardKey(id));
        DrawResponse failed = draw(user, id, "missing-award");
        assertEquals("奖品库存缺失是系统错误，不能消费为未中奖", "0001", failed.getCode());
        assertEquals(1, countTakes(user, id));
        assertEquals(2, leftCount(user, id));
        assertEquals(0, value("SELECT state FROM lottery.draw_participation WHERE u_id=? AND activity_id=?", user, id));
        // 测试已知初始库存且没有在途中奖订单，恢复后原请求继续同一参与。
        redis.opsForValue().set(awardKey(id), "10");
        assertWin(draw(user, id, "missing-award"));
        assertEquals(1, countTakes(user, id));
        assertEquals(2, leftCount(user, id));
        assertEquals("9", redis.opsForValue().get(awardKey(id)));
    }

    @Test
    public void rolledBackOrderRestoresItsAwardReservation() throws Exception {
        long id = createStarted(10, 3);
        String user = "rollback" + IDS.incrementAndGet();
        String physicalTable = "lottery.draw_order";
        String trigger = "lottery" + ".reject_order_" + id;
        // 在真实订单 INSERT 上制造 SQL 异常；同事务消费 take 的 UPDATE 必须回滚。
        update("CREATE TRIGGER " + trigger + " BEFORE INSERT ON " + physicalTable
                + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='order rollback probe'");
        try {
            assertEquals("0001", draw(user, id, "order-rollback").getCode());
            assertEquals(0, value("SELECT COUNT(*) FROM " + physicalTable + " WHERE u_id=?", user));
            assertEquals(0, value("SELECT state FROM " + "lottery"
                    + ".draw_participation WHERE u_id=? AND activity_id=?", user, id));
            assertEquals("已确认回滚的下单不能泄漏预扣奖品库存", "10", redis.opsForValue().get(awardKey(id)));
            org.mockito.Mockito.verify(outbox, org.mockito.Mockito.never())
                    .afterOrderCommit(org.mockito.ArgumentMatchers.any());
        } finally {
            update("DROP TRIGGER " + trigger);
        }
        assertWin(draw(user, id, "order-rollback"));
        assertEquals(1, countTakes(user, id));
        assertEquals(2, leftCount(user, id));
        assertEquals("9", redis.opsForValue().get(awardKey(id)));
    }

    @Test
    public void rolledBackParticipationDoesNotConsumeQuotaOrReserveAwards() throws Exception {
        long id = createStarted(10, 3);
        String user = "takefail" + IDS.incrementAndGet();
        String schema = "lottery";
        String trigger = schema + ".reject_take_" + id;
        // 次数扣减之后的参与 INSERT 失败，次数初始化和扣减必须随参与一起回滚。
        update("CREATE TRIGGER " + trigger + " BEFORE INSERT ON " + schema + ".draw_participation"
                + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='take rollback probe'");
        try {
            assertEquals("0001", draw(user, id, "take-rollback").getCode());
            assertEquals(0, countTakes(user, id));
            assertEquals(0, value("SELECT COUNT(*) FROM " + schema
                    + ".user_activity_quota WHERE u_id=? AND activity_id=?", user, id));
            assertNoActivityStock(id);
            assertEquals("10", redis.opsForValue().get(awardKey(id)));
        } finally {
            update("DROP TRIGGER " + trigger);
        }
        assertWin(draw(user, id, "take-rollback"));
        assertEquals(1, countTakes(user, id));
        assertEquals(2, leftCount(user, id));
        assertEquals("9", redis.opsForValue().get(awardKey(id)));
    }

    @Test
    public void createdActivityHasQuotaAndOwnPrizeStock() throws Exception {
        ActivityConfigReq req = config(10, 3, -60000);
        // 调用方不预填明细所属活动和剩余库存，创建服务必须完成初始化。
        assertNull(req.getActivityConfigRich().getActivityPrizeList().get(0).getActivityId());
        assertNull(req.getActivityConfigRich().getActivityPrizeList().get(0).getAwardSurplusCount());
        deploy.createActivity(req);
        long id = req.getActivityId();
        assertEquals(id, value("SELECT activity_id FROM lottery.activity_prize WHERE activity_id=?", id));
        assertEquals(3, value("SELECT take_count FROM lottery.activity WHERE activity_id=?", id));
        assertEquals(0, value("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='lottery'"
                + " AND table_name='activity' AND column_name IN ('stock_count','stock_surplus_count')"));
        assertEquals(10, value("SELECT award_count FROM lottery.activity_prize WHERE activity_id=?", id));
        assertEquals(10, value("SELECT award_surplus_count FROM lottery.activity_prize WHERE activity_id=?", id));
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
        job.advanceActivities();
        for (Long id : expected) assertEquals(Integer.valueOf(5), activityDao.queryActivityById(id).getState());
        job.advanceActivities();
        for (Long id : expected) {
            assertEquals("10", redis.opsForValue().get(awardKey(id)));
            assertNoActivityStock(id);
        }
    }

    @Test
    public void futureActivityIsNotStartedOrWarmed() throws Exception {
        ActivityConfigReq req = config(10, 3, 60000);
        deploy.createActivity(req);
        // 直接走状态机也不能绕过开始时间检查。
        assertFalse(lifecycle.transition(req.getActivityId(), Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
        assertEquals(Integer.valueOf(4), activityDao.queryActivityById(req.getActivityId()).getState());
        assertFalse(redis.hasKey(awardKey(req.getActivityId())));
        assertNoActivityStock(req.getActivityId());
    }

    @Test
    public void httpPipelineReplaysRequestsForMultipleUsers() throws Exception {
        long id = createStarted(30, 3);
        // 八位用户使用相同请求号，验证每位用户的回放及结算保持独立。
        for (int i = 0; i < 8; i++) {
            String user = "http" + IDS.incrementAndGet();
            DrawResponse first = draw(user, id, "same-request");
            assertWin(first);
            DrawResponse replay = draw(user, id, "same-request");
            assertEquals(first.getTakeId(), replay.getTakeId());
            assertEquals(first.getOrderId(), replay.getOrderId());
            assertEquals(1, countTakes(user, id));
            assertEquals(2, leftCount(user, id));
            recoverStock();
            awaitComplete(user, first.getOrderId());
            assertEquals(1, value("SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE order_id=?", first.getOrderId()));
            assertEquals(1, value("SELECT COUNT(*) FROM lottery.draw_order WHERE order_id=?", first.getOrderId()));
        }
        assertEquals(22, value("SELECT award_surplus_count FROM lottery.activity_prize WHERE activity_id=?", id));
        assertEquals("22", redis.opsForValue().get(awardKey(id)));
        assertNoActivityStock(id);
    }

    @Test
    public void replayRetainsOrderSnapshotAndReportsActualFulfillment() throws Exception {
        ActivityConfigReq req = config(10, 3, -60000);

        long id = req.getActivityId();
        deploy.createActivity(req);
        assertTrue(lifecycle.transition(id, Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
        String user = "snapshot" + IDS.incrementAndGet();

        DrawResponse first = draw(user, id, "snapshot");
        assertWin(first);
        // 改奖品目录后，历史订单回放仍读取下单时的名称和内容。
        update("UPDATE lottery.award SET award_name=?, award_content=? WHERE award_id=?",
                "更新后名称", "更新后内容", first.getAward().getAwardId());
        assertNull(order(user, first.getOrderId()).getFulfilledAt());
        assertNull(first.getAward().getGrantDate());

        recoverStock();
        awaitComplete(user, first.getOrderId());
        DrawOrder saved = order(user, first.getOrderId());
        assertNotNull(saved.getFulfilledAt());

        DrawResponse replay = draw(user, id, "snapshot");
        assertEquals(first.getOrderId(), replay.getOrderId());
        assertEquals(first.getAward().getAwardName(), replay.getAward().getAwardName());
        assertEquals(first.getAward().getAwardContent(), replay.getAward().getAwardContent());
        // 老客户端仍通过 grantDate 获取回放时的实际完成时间。
        assertEquals(saved.getFulfilledAt().toString(), replay.getAward().getGrantDate());
        assertEquals(2, leftCount(user, id));
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
        recoverStock();
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
            assertEquals(String.valueOf(50 - won.size()), redis.opsForValue().get(awardKey(id)));
            recoverStock();
            for (DrawResponse result : won) awaitComplete(user, result.getOrderId());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void soldOutAwardsStillConsumeQuotaAndReplayLosingResults() throws Exception {
        long id = createStarted(1, 3);
        String user = "empty" + IDS.incrementAndGet();
        DrawResponse first = draw(user, id, "first");
        assertWin(first);
        DrawResponse second = draw(user, id, "second");
        assertEquals(Constants.ResponseCode.LOSING_DRAW.getCode(), second.getCode());
        assertFalse(second.isWin());
        assertEquals(second.getTakeId(), draw(user, id, "second").getTakeId());
        assertEquals(2, countTakes(user, id));
        assertEquals(1, leftCount(user, id));
        assertEquals(Constants.ResponseCode.LOSING_DRAW.getCode(), draw(user, id, "third").getCode());
        assertEquals(Constants.ResponseCode.NO_UPDATE.getCode(), draw(user, id, "fourth").getCode());
        assertEquals(3, countTakes(user, id));
        assertEquals(0, leftCount(user, id));
        assertEquals(1, value("SELECT COUNT(*) FROM lottery.draw_order WHERE u_id=? AND activity_id=?", user, id));
        assertNoActivityStock(id);
        recoverStock();
        awaitComplete(user, first.getOrderId());
        assertEquals(0, value("SELECT award_surplus_count FROM lottery.activity_prize WHERE activity_id=?", id));
        assertEquals("0", redis.opsForValue().get(awardKey(id)));
    }

    @Test
    public void noPrizeConsumesPersonalQuotaAndReplaysTheSameTake() throws Exception {
        ActivityConfigReq req = config(10, 3, -60000);
        // 概率余量全部落入 NO_PRIZE，仍调用真实快照构建和抽样算法。
        req.getActivityConfigRich().getActivityPrizeList().get(0).setAwardRate(BigDecimal.ZERO);
        deploy.createActivity(req);
        long id = req.getActivityId();
        assertTrue(lifecycle.transition(id, Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
        String user = "losing" + IDS.incrementAndGet();
        DrawResponse losing = draw(user, id, "no-prize");
        assertEquals(Constants.ResponseCode.LOSING_DRAW.getCode(), losing.getCode());
        assertEquals(losing.getTakeId(), draw(user, id, "no-prize").getTakeId());
        assertEquals(1, countTakes(user, id));
        assertEquals(2, leftCount(user, id));
        assertEquals(1, value("SELECT state FROM lottery.draw_participation WHERE u_id=? AND activity_id=?", user, id));
        assertEquals(0, value("SELECT COUNT(*) FROM lottery.draw_order WHERE u_id=? AND activity_id=?", user, id));
        assertEquals("10", redis.opsForValue().get(awardKey(id)));
        assertNoActivityStock(id);
    }

    @Test
    public void sameAwardUsesIndependentProbabilityAndInventory() throws Exception {
        long first = createStarted(10, 3);
        long second = createActivityWithExistingAward(first, 3, BigDecimal.ZERO);
        long third = createActivityWithExistingAward(first, 2, BigDecimal.ONE);
        String user = "isolation" + IDS.incrementAndGet();
        assertTrue(lifecycle.transition(second, Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
        assertTrue(lifecycle.transition(third, Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
        DrawResponse a = draw(user, first, "first-pool");
        assertWin(a);
        assertEquals("D001", draw(user, second, "zero-rate-pool").getCode());
        DrawResponse b = draw(user, third, "third-pool");
        assertWin(b);
        // 三个奖池引用同一个目录奖品，但概率、实时预扣和数据库结算各自独立。
        String award = "p" + first;
        assertEquals("9", redis.opsForValue().get(awardKey(first)));
        assertEquals("3", redis.opsForValue().get(Constants.RedisKey.KEY_LOTTERY_ACTIVITY_PRIZE_STOCK_COUNT(second, award)));
        assertEquals("1", redis.opsForValue().get(Constants.RedisKey.KEY_LOTTERY_ACTIVITY_PRIZE_STOCK_COUNT(third, award)));
        assertEquals(2, leftCount(user, first));
        assertEquals(2, leftCount(user, second));
        assertEquals(2, leftCount(user, third));
        recoverStock();
        awaitComplete(user, a.getOrderId());
        awaitComplete(user, b.getOrderId());
        assertEquals(9, value("SELECT award_surplus_count FROM lottery.activity_prize WHERE activity_id=?", first));
        assertEquals(3, value("SELECT award_surplus_count FROM lottery.activity_prize WHERE activity_id=?", second));
        assertEquals(1, value("SELECT award_surplus_count FROM lottery.activity_prize WHERE activity_id=?", third));
        assertEquals(1, value("SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE activity_id=?", first));
        assertEquals(0, value("SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE activity_id=?", second));
        assertEquals(1, value("SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE activity_id=?", third));
    }

    @Test
    public void legacyKafkaStrategyIdCannotRouteToAnotherActivitiesPool() throws Exception {
        long first = createStarted(10, 3);
        long other = createActivityWithExistingAward(first, 7, BigDecimal.ONE);
        assertTrue(lifecycle.transition(other, Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
        String user = "legacy" + IDS.incrementAndGet();
        DrawResponse won = draw(user, first, "old-message");
        assertWin(won);
        // 故意让旧 strategyId 命中另一个真实活动，旧 awardId 同样不参与路由。
        com.alibaba.fastjson.JSONObject message = new com.alibaba.fastjson.JSONObject();
        message.put("uId", user);
        message.put("orderId", won.getOrderId());
        message.put("strategyId", other);
        message.put("awardId", "p" + first);
        String json = message.toJSONString();
        String topic = "lottery_strategy_award_stock";
        RecordMetadata legacy = kafka.send(topic, won.getOrderId().toString(), json)
                .get(30, TimeUnit.SECONDS).getRecordMetadata();
        awaitStockOffset(legacy);
        awaitComplete(user, won.getOrderId());
        // 再发新协议消息，仍由订单与唯一账本收敛，另一个活动余额不变。
        awaitStockOffset(producer.sendStockSettlement(settlement(user, first, won.getOrderId()))
                .get(30, TimeUnit.SECONDS).getRecordMetadata());
        assertEquals(first, value("SELECT activity_id FROM lottery.award_stock_settlement WHERE order_id=?", won.getOrderId()));
        assertEquals(1, value("SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE order_id=?", won.getOrderId()));
        assertEquals(9, value("SELECT award_surplus_count FROM lottery.activity_prize WHERE activity_id=?", first));
        assertEquals(7, value("SELECT award_surplus_count FROM lottery.activity_prize WHERE activity_id=?", other));
    }

    @Test
    public void missingRunningStockDoesNotStopAnotherPoolOrAllowRewarm() throws Exception {
        long first = createStarted(10, 3);
        String user = "poolgap" + IDS.incrementAndGet();
        DrawResponse pending = draw(user, first, "unsettled");
        assertWin(pending);
        long second = createActivityWithExistingAward(first, 3, BigDecimal.ONE);
        redis.delete(awardKey(first));
        assertThrows(IllegalStateException.class, () -> activities.warmupAwardStockForActivity(first));
        assertFalse(lifecycle.transition(first, Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
        assertTrue(lifecycle.transition(second, Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
        assertFalse(redis.hasKey(awardKey(first)));
        assertTrue(lifecycle.transition(first, Constants.ActivityState.RUNNING, Constants.ActivityState.CLOSED));
        assertThrows(IllegalStateException.class, () -> activities.warmupAwardStockForActivity(first));
        assertFalse(redis.hasKey(awardKey(first)));
        recoverStock();
        awaitComplete(user, pending.getOrderId());
        assertEquals(9, value("SELECT award_surplus_count FROM lottery.activity_prize WHERE activity_id=?", first));
        assertFalse(redis.hasKey(awardKey(first)));
    }

    @Test
    public void concurrentStartsOfIndependentPoolsPrewarmEachBalance() throws Exception {
        ActivityConfigReq req = config(10, 3, -60000);
        deploy.createActivity(req);
        long first = req.getActivityId();
        long second = createActivityWithExistingAward(first, 7, BigDecimal.ONE);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> starts = new ArrayList<>();
            for (long id : new long[]{first, second}) starts.add(pool.submit(() -> {
                start.await();
                return lifecycle.transition(id, Constants.ActivityState.READY, Constants.ActivityState.RUNNING);
            }));
            start.countDown();
            for (Future<Boolean> result : starts) assertTrue(result.get(30, TimeUnit.SECONDS));
            assertEquals("10", redis.opsForValue().get(awardKey(first)));
            assertEquals("7", redis.opsForValue().get(
                    Constants.RedisKey.KEY_LOTTERY_ACTIVITY_PRIZE_STOCK_COUNT(second, "p" + first)));
            assertNoActivityStock(first);
            assertNoActivityStock(second);
        } finally { pool.shutdownNow(); }
    }

    @Test
    public void warmupUsesRemainingPrizeStockAndRejectsInvalidConfiguration() throws Exception {
        ActivityConfigReq req = config(10, 3, -60000);
        deploy.createActivity(req);
        long id = req.getActivityId();
        update("UPDATE lottery.activity_prize SET award_surplus_count=7 WHERE activity_id=?", id);
        assertEquals(1, activities.warmupAwardStockForActivity(id));
        assertEquals("7", redis.opsForValue().get(awardKey(id)));
        assertTrue(lifecycle.transition(id, Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
        assertEquals("7", redis.opsForValue().get(awardKey(id)));
        // 没有奖池明细的活动无法启动。
        ActivityVO empty = config(10, 3, -60000).getActivityConfigRich().getActivity();
        activities.addActivity(empty);
        long invalid = empty.getActivityId();
        assertThrows(IllegalStateException.class, () -> lifecycle.transition(invalid,
                Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
        assertEquals(Integer.valueOf(4), activityDao.queryActivityById(invalid).getState());
        // 用例制造的无效活动不进入后续调度扫描，按合法生命周期关闭。
        assertTrue(lifecycle.transition(invalid, Constants.ActivityState.READY, Constants.ActivityState.CLOSED));
    }

    @Test
    public void redisOutagePreventsStartAndRecoversAfterRestart() throws Exception {
        ActivityConfigReq req = config(10, 3, -60000);
        deploy.createActivity(req);
        try {
            // 真实停止专用 Redis，预热必须失败且数据库仍为 PASS。
            MqMiddlewareE2eManualIT.compose("stop", "redis");
            assertThrows(RuntimeException.class, () -> lifecycle.transition(req.getActivityId(),
                    Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
            assertEquals(Integer.valueOf(4), activityDao.queryActivityById(req.getActivityId()).getState());
        } finally {
            MqMiddlewareE2eManualIT.compose("up", "-d", "--wait", "redis");
        }
        assertTrue(lifecycle.transition(req.getActivityId(), Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
        assertNoActivityStock(req.getActivityId());
        assertEquals("10", redis.opsForValue().get(awardKey(req.getActivityId())));
    }

    @Test
    public void partialWarmupFailureKeepsPassAndSetNxPreservesExistingStock() throws Exception {
        ActivityConfigReq req = config(10, 3, -60000);
        // 两个奖品使故障仍发生在第二次写入，验证真实的部分预热恢复。
        ActivityPrizeVO second = new ActivityPrizeVO();
        second.setAwardId("q" + req.getActivityId());
        second.setAwardCount(5);
        second.setAwardRate(BigDecimal.ZERO);
        req.getActivityConfigRich().setActivityPrizeList(Arrays.asList(
                req.getActivityConfigRich().getActivityPrizeList().get(0), second));
        AwardVO secondAward = new AwardVO();
        secondAward.setAwardId(second.getAwardId());
        secondAward.setAwardName("零概率测试券");
        secondAward.setAwardType(3);
        secondAward.setAwardContent("项目已有模拟发奖渠道");
        req.getActivityConfigRich().setAwardList(Arrays.asList(
                req.getActivityConfigRich().getAwardList().get(0), secondAward));
        deploy.createActivity(req);
        long id = req.getActivityId();
        String secondKey = Constants.RedisKey.KEY_LOTTERY_ACTIVITY_PRIZE_STOCK_COUNT(id, second.getAwardId());
        try {
            // 真实 Redis ACL 只允许写第一个奖品 Key，第二个奖品 SETNX 必须失败。
            redisCommand("ACL", "SETUSER", "default", "resetkeys", "%R~*", "%W~" + awardKey(id));
            assertThrows(RuntimeException.class, () -> lifecycle.transition(id, Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
            assertEquals(Integer.valueOf(4), activityDao.queryActivityById(id).getState());
            assertEquals("10", redis.opsForValue().get(awardKey(id)));
            assertFalse(redis.hasKey(secondKey));
        } finally {
            redisCommand("ACL", "SETUSER", "default", "resetkeys", "~*");
        }
        // 已存在 Key 的实时值必须保留；恢复启动只补上缺失奖品 Key。
        redis.opsForValue().set(awardKey(id), "7");
        assertTrue(lifecycle.transition(id, Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
        assertEquals("7", redis.opsForValue().get(awardKey(id)));
        assertEquals("5", redis.opsForValue().get(secondKey));
        assertNoActivityStock(id);
        redis.opsForValue().set(awardKey(id), "8");
        assertFalse(lifecycle.transition(id, Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
        assertEquals("8", redis.opsForValue().get(awardKey(id)));
        // 运行期缺失奖品库存不能由重复启动或直接预热复活。
        redis.delete(awardKey(id));
        assertFalse(lifecycle.transition(id, Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
        assertThrows(IllegalStateException.class, () -> activities.warmupAwardStockForActivity(id));
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
                return lifecycle.transition(req.getActivityId(), Constants.ActivityState.READY, Constants.ActivityState.RUNNING);
            }));
            start.countDown();
            int started = 0;
            for (Future<Boolean> future : futures) if (future.get(30, TimeUnit.SECONDS)) started++;
            assertEquals(1, started);
            redis.opsForValue().decrement(awardKey(req.getActivityId()));
            job.advanceActivities();
            assertEquals("9", redis.opsForValue().get(awardKey(req.getActivityId())));
        } finally { pool.shutdownNow(); }
    }

    @Test
    public void creationRejectsAlreadyRunningAndUnrepresentableProbability() throws Exception {
        ActivityConfigReq req = config(10, 3, -60000);
        ActivityConfigRich config = req.getActivityConfigRich();
        config.getActivity().setState(Constants.ActivityState.RUNNING.getCode());
        // 创建不能绕过启动前的库存预热。
        assertThrows(IllegalArgumentException.class, () -> deploy.createActivity(req));
        config.getActivity().setState(Constants.ActivityState.READY.getCode());
        // 不允许由 MySQL 舍入改变概率；六位小数可以无损保存。
        config.getActivityPrizeList().get(0).setAwardRate(new BigDecimal("0.1234567"));
        assertThrows(IllegalArgumentException.class, () -> deploy.createActivity(req));
        assertEquals(0, value("SELECT COUNT(*) FROM lottery.activity WHERE activity_id=?", req.getActivityId()));
        config.getActivityPrizeList().get(0).setAwardRate(new BigDecimal("0.123456"));
        deploy.createActivity(req);
        assertEquals(123456, value("SELECT award_rate*1000000 FROM lottery.activity_prize WHERE activity_id=?",
                req.getActivityId()));
    }

    @Test
    public void duplicatePrizeRollsBackActivityAndAwards() throws Exception {
        ActivityConfigReq req = config(10, 3, -60000);
        // 同活动重复奖品触发唯一约束，先插入的活动和奖品也必须回滚。
        ActivityPrizeVO detail = req.getActivityConfigRich().getActivityPrizeList().get(0);
        detail.setAwardRate(new BigDecimal("0.5"));
        req.getActivityConfigRich().setActivityPrizeList(Arrays.asList(detail, detail));
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> deploy.createActivity(req));
        assertEquals(0, value("SELECT COUNT(*) FROM lottery.activity WHERE activity_id=?", req.getActivityId()));
        assertEquals(0, value("SELECT COUNT(*) FROM lottery.award WHERE award_id=?", "p" + req.getActivityId()));
        assertEquals(0, value("SELECT COUNT(*) FROM lottery.activity_prize WHERE activity_id=?", req.getActivityId()));
    }

    @Test
    public void stockGapIsDurableBlockedAndReconciledOrderCanFinish() throws Exception {
        long id = createStarted(10, 3);
        String user = "gap" + IDS.incrementAndGet();
        DrawResponse result = draw(user, id, "gap-request");
        assertWin(result);
        // 已预扣且有持久订单，但全局库存被置为零，模拟真实库存分歧。
        update("UPDATE lottery.activity_prize SET award_surplus_count=0 WHERE activity_id=?", id);
        recoverStock();
        await("库存分歧持久化为 BLOCKED", () -> order(user, result.getOrderId()).getStockSettlementEventStatus() == 4);
        assertEquals(0, value("SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE order_id=?", result.getOrderId()));
        assertEquals(Integer.valueOf(0), order(user, result.getOrderId()).getAwardFulfillmentStatus());
        // 真实 broker 重复投递也可以 ACK，但不能产生账本或发奖。
        AwardStockSettlementVO settlement = settlement(user, id, result.getOrderId());
        RecordMetadata record = producer.sendStockSettlement(settlement).get(30, TimeUnit.SECONDS).getRecordMetadata();
        awaitStockOffset(record);
        for (int i = 0; i < 3; i++) recoverStock();
        assertFalse(orderRepository.scanOutbox(com.anontokyo.lottery.domain.award.repository.IOrderRepository.OutboxKind.STOCK, 200).stream()
                .anyMatch(row -> row.getOrderId().equals(result.getOrderId())));
        // 迟到发送成功/失败不可解除待对账状态。
        orderRepository.updateStockSettlementEventStatus(user, result.getOrderId(), 3);
        orderRepository.updateStockSettlementEventStatus(user, result.getOrderId(), 2);
        assertEquals(Integer.valueOf(4), order(user, result.getOrderId()).getStockSettlementEventStatus());
        // 人工确认并修复全局库存，再按订单 CAS 释放原 Outbox。
        update("UPDATE lottery.activity_prize SET award_surplus_count=10 WHERE activity_id=?", id);
        update("UPDATE lottery.draw_order SET stock_settlement_event_status=0,stock_retry_at=NOW(3),stock_attempt=stock_attempt+1 WHERE u_id=? AND order_id=? AND stock_settlement_event_status=4", user, result.getOrderId());
        recoverStock();
        awaitComplete(user, result.getOrderId());
        assertEquals(1, value("SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE order_id=?", result.getOrderId()));
        assertEquals(9, value("SELECT award_surplus_count FROM lottery.activity_prize WHERE activity_id=?", id));
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
            // 直接调用生产消费者验证 ACK 边界，但 SQL、事务均真实执行。
            assertThrows(org.springframework.dao.DataAccessException.class, () -> stockListener.onMessage(
                    new ConsumerRecord<>("lottery_strategy_award_stock", 0, 0, String.valueOf(result.getOrderId()), JSON.toJSONString(settlement)),
                    () -> acknowledged.set(true), "lottery_strategy_award_stock"));
            assertFalse("SQL 故障不能 ACK", acknowledged.get());
            assertEquals(Integer.valueOf(0), order(user, result.getOrderId()).getStockSettlementEventStatus());
            assertEquals(10, value("SELECT award_surplus_count FROM lottery.activity_prize WHERE activity_id=?", id));
        } finally {
            // 仅恢复专用测试实例的表，失败不可留下已改名的 fixture。
            update("RENAME TABLE lottery.award_stock_settlement_unavailable TO lottery.award_stock_settlement");
            listeners.start();
        }
        assertEquals(0, value("SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE order_id=?", result.getOrderId()));
        // 原订单仍处于可补偿 INIT，由真实 Kafka 重新投递完成，而非测试直接结算。
        recoverStock();
        awaitComplete(user, result.getOrderId());
        assertEquals(1, value("SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE order_id=?", result.getOrderId()));
        assertEquals(9, value("SELECT award_surplus_count FROM lottery.activity_prize WHERE activity_id=?", id));
    }

    @Test
    public void expiredPassedAndRunningActivitiesCloseWithoutWarmup() throws Exception {
        ActivityConfigReq passed = config(10, 3, -120000);
        deploy.createActivity(passed);
        update("UPDATE lottery.activity SET end_date_time=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE activity_id=?", passed.getActivityId());
        long running = createStarted(10, 3);
        update("UPDATE lottery.activity SET end_date_time=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE activity_id=?", running);
        job.advanceActivities();
        assertEquals(Integer.valueOf(7), activityDao.queryActivityById(passed.getActivityId()).getState());
        assertFalse(redis.hasKey(awardKey(passed.getActivityId())));
        assertEquals(Integer.valueOf(7), activityDao.queryActivityById(running).getState());
        assertEquals("10", redis.opsForValue().get(awardKey(running)));
    }

    private void recoverStock() throws Exception {
        // 仅使本测试中跳过实时调度的首发订单到期，租约、超时与回调仍由生产实现处理。
        update("UPDATE lottery.draw_order SET stock_retry_at=NOW(3)"
                + " WHERE stock_settlement_event_status=0 AND stock_attempt=1 AND stock_retry_at IS NOT NULL");
        job.recoverStock();
    }

    @Test
    public void committedOrderIsSentWithoutCallingTheScheduler() throws Exception {
        org.mockito.Mockito.doCallRealMethod().when(outbox).afterOrderCommit(org.mockito.ArgumentMatchers.any());
        long id = createStarted(10, 3);
        String user = "realtime" + IDS.incrementAndGet();
        DrawResponse result = draw(user, id, "realtime-commit");
        assertWin(result);
        // 禁用调度的测试上下文仍能完成，只有真实提交事件可触发本单投递。
        awaitComplete(user, result.getOrderId());
        assertEquals(1, value("SELECT stock_attempt FROM lottery.draw_order WHERE order_id=?", result.getOrderId()));
        assertEquals(1, value("SELECT fulfillment_attempt FROM lottery.draw_order WHERE order_id=?", result.getOrderId()));
        assertEquals(1, value("SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE order_id=?", result.getOrderId()));
    }

    @Test
    public void registeredOrderEventDoesNotRunWhenOuterTransactionRollsBack() throws Exception {
        long id = createStarted(10, 3);
        String user = "outer" + IDS.incrementAndGet();
        PartakeResult participation = partake.doPartake(new PartakeReq(user, id, "outer-rollback"));
        AwardOrderReq order = new AwardOrderReq();
        order.setuId(user);
        order.setTakeId(participation.getTakeId());
        order.setActivityId(id);
        order.setAwardId("p" + id);
        order.setAwardType(3);

        AwardOrderResult result = transaction.execute(status -> {
            AwardOrderResult created = awardOrder.placeOrder(order);
            assertEquals("0000", created.getCode());
            // 此时订单已 INSERT 且事件已注册，外层事务实际回滚。
            status.setRollbackOnly();
            return created;
        });
        org.mockito.Mockito.verify(outbox, org.mockito.Mockito.never())
                .afterOrderCommit(org.mockito.ArgumentMatchers.any());
        assertNull(order(user, result.getAwardFulfillmentEvent().getOrderId()));
        assertEquals(0, value("SELECT state FROM lottery.draw_participation WHERE take_id=?", participation.getTakeId()));
    }

    private AwardStockSettlementVO settlement(String user, long id, long orderId) {
        AwardStockSettlementVO result = new AwardStockSettlementVO();
        result.setuId(user);
        result.setOrderId(orderId);
        result.setActivityId(id);
        result.setAwardId("p" + id);
        return result;
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
        List<String> command = new ArrayList<>(Arrays.asList("docker", "exec", MqMiddlewareE2eManualIT.PROJECT + "-redis-1", "redis-cli"));
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
        activity.setTakeCount(quota);
        activity.setState(4);
        activity.setCreator("e2e");
        ActivityPrizeVO detail = new ActivityPrizeVO();
        detail.setAwardId("p" + id);
        detail.setAwardCount(stock);
        // 100% 中奖仅为稳定 fixture，不替换真实抽样实现。
        detail.setAwardRate(new BigDecimal("1.00"));
        AwardVO award = new AwardVO();
        award.setAwardId(detail.getAwardId());
        award.setAwardType(3);
        award.setAwardName("本地模拟券");
        award.setAwardContent("项目已有模拟发奖渠道");
        return new ActivityConfigReq(id, new ActivityConfigRich(activity, Collections.singletonList(detail), Collections.singletonList(award)));
    }

    private long createStarted(int stock, int quota) throws Exception {
        ActivityConfigReq req = config(stock, quota, -60000);
        deploy.createActivity(req);
        assertTrue(lifecycle.transition(req.getActivityId(), Constants.ActivityState.READY, Constants.ActivityState.RUNNING));
        return req.getActivityId();
    }

    private long createActivityWithExistingAward(long first, int stock, BigDecimal rate) {
        ActivityConfigReq req = config(stock, 3, -60000);
        ActivityConfigRich pool = req.getActivityConfigRich();
        pool.setAwardList(Collections.emptyList());
        pool.getActivityPrizeList().get(0).setAwardId("p" + first);
        pool.getActivityPrizeList().get(0).setAwardRate(rate);
        deploy.createActivity(req);
        return req.getActivityId();
    }

    @Test
    public void admissionOutageHasNoWritesAndOriginalRequestRecovers() throws Exception {
        long id = createStarted(10, 3);
        String user = "limit" + IDS.incrementAndGet();
        try {
            // 专用 Redis 停机时请求必须在 TX1 前失败，次数行和参与都不存在。
            MqMiddlewareE2eManualIT.compose("stop", "redis");
            ResponseEntity<DrawResponse> rejected = drawResponse(user, id, "retry-after-outage");
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejected.getStatusCode());
            assertEquals("L004", rejected.getBody().getCode());
            assertEquals("retry-after-outage", rejected.getBody().getRequestId());
            assertEquals(0, countTakes(user, id));
            assertEquals(0, value("SELECT COUNT(*) FROM lottery.user_activity_quota WHERE u_id=? AND activity_id=?", user, id));
        } finally {
            MqMiddlewareE2eManualIT.compose("up", "-d", "--wait", "redis");
        }
        assertEquals("10", redis.opsForValue().get(awardKey(id)));
        DrawResponse first = draw(user, id, "retry-after-outage");
        assertWin(first);
        assertEquals(first.getOrderId(), draw(user, id, "retry-after-outage").getOrderId());
        assertEquals(1, countTakes(user, id));
        assertEquals(2, leftCount(user, id));
        assertEquals("9", redis.opsForValue().get(awardKey(id)));
        recoverStock();
        awaitComplete(user, first.getOrderId());
    }

    private DrawResponse draw(String user, long id, String requestId) {
        return drawResponse(user, id, requestId).getBody();
    }

    private ResponseEntity<DrawResponse> drawResponse(String user, long id, String requestId) {
        DrawRequest request = new DrawRequest();
        request.setUserId(user);
        request.setActivityId(id);
        request.setRequestId(requestId);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Test-Principal", user);
        return http.postForEntity("/api/v1/draw", new HttpEntity<>(request, headers), DrawResponse.class);
    }

    @TestConfiguration
    public static class TestIdentity {
        @Bean
        FilterRegistrationBean<Filter> testIdentityFilter() {
            // 只在隔离测试容器的 loopback HTTP 端口装配测试身份。
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
            registration.setFilter((request, response, chain) -> {
                HttpServletRequest original = (HttpServletRequest) request;
                chain.doFilter(new HttpServletRequestWrapper(original) {
                    @Override public java.security.Principal getUserPrincipal() {
                        String user = original.getHeader("X-Test-Principal");
                        return user == null ? null : () -> user;
                    }
                }, response);
            });
            registration.addUrlPatterns("/api/v1/draw");
            return registration;
        }
    }

    private void assertWin(DrawResponse result) {
        assertNotNull(result);
        assertEquals(result.getInfo(), Constants.ResponseCode.SUCCESS.getCode(), result.getCode());
        assertTrue(result.isWin());
        assertNotNull(result.getOrderId());
    }

    private DrawOrder order(String user, long orderId) {
        DrawOrder query = new DrawOrder();
        query.setuId(user);
        query.setOrderId(orderId);
        return orders.queryDrawOrderByOrderId(query);
    }

    private void awaitComplete(String user, long orderId) throws Exception {
        await("结算/发奖完成 " + orderId, () -> {
            DrawOrder row = order(user, orderId);
            return row.getStockSettlementEventStatus() == 1 && row.getFulfillmentEventStatus() == 1 && row.getAwardFulfillmentStatus() == 1;
        });
    }

    private long countTakes(String user, long id) throws Exception {
        return value("SELECT COUNT(*) FROM lottery.draw_participation WHERE u_id=? AND activity_id=?", user, id);
    }

    private long leftCount(String user, long id) throws Exception {
        return value("SELECT left_count FROM lottery.user_activity_quota WHERE u_id=? AND activity_id=?", user, id);
    }

    private void assertNoActivityStock(long id) {
        // 只检查旧 Key 不存在；业务代码不得再创建或操作活动总库存。
        assertFalse(redis.hasKey("lottery_activity_stock_count_" + id));
    }
    private String awardKey(long id) { return Constants.RedisKey.KEY_LOTTERY_ACTIVITY_PRIZE_STOCK_COUNT(id, "p" + id); }

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
