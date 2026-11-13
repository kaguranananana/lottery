package com.anontokyo.lottery.test.manual;

import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.draw.model.req.DrawReq;
import com.anontokyo.lottery.domain.draw.model.snapshot.ActivityPrizeSnapshot;
import com.anontokyo.lottery.domain.draw.service.draw.impl.DrawExecutor;
import com.anontokyo.lottery.infrastructure.repository.ActivityPrizeRepository;
import com.anontokyo.lottery.infrastructure.utils.RedisUtil;
import org.junit.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 在专用 Redis 上验证库存错误语义及并发扣减，不使用模拟 Redis 代替 Lua 执行。 */
public class RedisStockFailureManualIT {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis());
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private ActivityPrizeRepository strategies;
    private Long id;
    private String awardKey;

    @BeforeClass
    public static void connect() {
        // 显式限定已有 E2E 的测试端口，不操作业务 Redis。
        assertEquals("16379", System.getProperty("lottery.it.redis.port"));
        connections = new LettuceConnectionFactory("127.0.0.1", 16379);
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
    }

    @Before
    public void fixture() {
        id = IDS.incrementAndGet();
        awardKey = Constants.RedisKey.KEY_LOTTERY_ACTIVITY_PRIZE_STOCK_COUNT(id, "probe");
        RedisUtil commands = new RedisUtil();
        ReflectionTestUtils.setField(commands, "stringRedisTemplate", redis);
        strategies = new ActivityPrizeRepository();
        ReflectionTestUtils.setField(strategies, "redisUtil", commands);
    }

    @After
    public void cleanupKeys() {
        // 只删除本用例创建的奖品 Key，保留其它测试数据。
        redis.delete(awardKey);
    }

    @AfterClass
    public static void close() {
        if (connections != null) connections.destroy();
    }

    @Test
    public void missingAwardStockIsAnErrorInsteadOfALosingDraw() {
        ActivityPrizeSnapshot snapshot = new ActivityPrizeSnapshot();
        snapshot.setAwardIds(new String[]{"probe"});
        snapshot.setCumulativeWeights(new long[]{1_000_000L});
        // 仅固定抽样配置；扣减使用真实仓储和真实 Redis。
        ActivityPrizeRepository source = spy(strategies);
        doReturn(snapshot).when(source).queryActivityPrizeSnapshot(id);
        DrawExecutor draw = new DrawExecutor();
        ReflectionTestUtils.setField(draw, "activityPrizeRepository", source);
        assertThrows(IllegalStateException.class, () -> draw.doDrawExec(new DrawReq("stock-probe", id)));
        assertFalse("缺失库存不得自动重建", redis.hasKey(awardKey));
    }

    @Test
    public void awardRecoveryMustNotRecreateMissingInventory() {
        assertThrows(IllegalStateException.class, () -> strategies.recoverActivityPrizeStockByRedis(id, "probe"));
        assertFalse("回补不能用 INCR 把缺失库存复活为 1", redis.hasKey(awardKey));
    }

    @Test
    public void awardRecoveryRestoresExactlyOneReservation() {
        redis.opsForValue().set(awardKey, "9");
        strategies.recoverActivityPrizeStockByRedis(id, "probe");
        assertEquals("10", redis.opsForValue().get(awardKey));
    }

    @Test
    public void awardRecoveryFailureMustReachTheCaller() {
        // 使用真实 Redis WRONGTYPE 错误，验证回补异常没有被日志吞掉。
        redis.opsForList().leftPush(awardKey, "wrong-type");
        assertThrows(RuntimeException.class, () -> strategies.recoverActivityPrizeStockByRedis(id, "probe"));
    }

    @Test
    public void soldOutAwardRemainsZeroAndReturnsNoPrize() {
        redis.opsForValue().set(awardKey, "0");
        assertFalse(strategies.deductStockByRedis(id, "probe").isSuccess());
        assertEquals("0", redis.opsForValue().get(awardKey));
    }

    @Test
    public void competingAwardDeductionsSellExactlyTheAvailableStock() throws Exception {
        redis.opsForValue().set(awardKey, "3");
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Future<Boolean>> requests = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                requests.add(pool.submit(() -> strategies.deductStockByRedis(id, "probe").isSuccess()));
            }
            int accepted = 0;
            // Future.get 让任意工作线程的异常都导致测试失败。
            for (Future<Boolean> request : requests) if (request.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(3, accepted);
            assertEquals("0", redis.opsForValue().get(awardKey));
        } finally {
            pool.shutdownNow();
        }
    }
}
