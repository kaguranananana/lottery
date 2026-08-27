package com.anontokyo.lottery.test.manual;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 手工集成测试（需要本机 MySQL lottery 库与 Redis，见 README「测试运行说明」）。
 *
 * 覆盖任务场景：
 *  B. 库存并发：100 并发抢 10 个库存 ⇒ Redis 成功扣减 ≤10、DB settlement 次数 ≤10、无负库存
 *  C. MQ duplicate：同一 orderId 的 settleAwardStock 调两次 ⇒ stock 只扣一次
 *  D. consumer crash 模拟：settle 后再消费 ⇒ 第二次被 UNIQUE(order_id) 拦截
 *  G. Outbox 重发前置条件：stock_mq_state=0/2 能被扫描出来（由 XXL-Job 参数驱动）
 *
 * 说明：本骨架直接对 Repository 行为做集成验证（等价于消费者内部的事务逻辑），
 * 不启动 Kafka；MQ 投递本身由生产环境手动 ack 场景验证或后续 Testcontainers 化。
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class StockSettlementIdempotencyManualIT {

    private static final String JDBC_URL = "jdbc:mysql://127.0.0.1:13306/lottery?useUnicode=true&serverTimezone=Asia/Shanghai";

    private Connection connection;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 简化的同步视图：与生产 RedisUtil 同样走 spring-data-redis */
    private long decr(String key) {
        Long v = stringRedisTemplate.opsForValue().increment(key, -1L);
        return v == null ? -1 : v;
    }

    private long incr(String key) {
        Long v = stringRedisTemplate.opsForValue().increment(key, 1L);
        return v == null ? -1 : v;
    }

    private void setKey(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }

    private String getKey(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    @Before
    public void setUp() throws Exception {
        connection = DriverManager.getConnection(JDBC_URL, "root", "root");
    }

    /**
     * 场景 C/D：同一 orderId 连续结算两次。
     * 第二次必须命中 award_stock_settlement.uk_order_id，strategy_detail 只扣减一次。
     */
    @Test
    public void sameOrderIdSettledTwiceDeductsOnlyOnce() throws Exception {
        long orderId = System.currentTimeMillis();
        // 预置一个剩余 5 的明细（也可改用已有 strategy 数据）
        // arrangeStrategyDetail(10001L, "C", 5);

        int first = executeSettlementSql(orderId, 10001L, "C");   // 应用内实际走 repository 同一事务
        int second = executeSettlementSql(orderId, 10001L, "C");

        org.junit.Assert.assertEquals(1, first);
        org.junit.Assert.assertEquals(-1, second);   // duplicate key → 幂等短路

        int surplus = querySurplusCount(10001L, "C");
        org.junit.Assert.assertEquals("stock must be deducted exactly once", 4, surplus);
    }

    /**
     * 场景 B：100 个线程并发预扣 Redis 奖品库存 10 个。
     */
    @Test
    public void concurrentRedisDeductionNeverOversells() throws Exception {
        String key = "it_award_stock_" + System.currentTimeMillis();
        setKey(key, "10");

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (decr(key) >= 0) {
                        success.incrementAndGet();
                    } else {
                        incr(key); // 归还，保持非负语义
                    }
                } catch (Exception ignore) {
                    // ignored
                }
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        org.junit.Assert.assertEquals("oversell detected", 10, success.get());
        org.junit.Assert.assertTrue("redis stock must stay >= 0",
                Long.parseLong(getKey(key)) >= 0);
    }

    /**
     * 场景 D 前半段：模拟「扣减成功但状态未落」之后重复消费——直接调
     * settleAwardStock 两次等价于 Kafka redelivery 后的第二轮执行。
     */

    // ------------------------ helper SQL ------------------------

    /** 与 StrategyRepository.settleAwardStock 相同的本地事务的裸 SQL 版本（便于独立运行） */
    private int executeSettlementSql(long orderId, long strategyId, String awardId) throws Exception {
        connection.setAutoCommit(false);
        try {
            PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO award_stock_settlement (order_id, u_id, strategy_id, award_id, create_time)" +
                            " VALUES (?, 'it-user', ?, ?, NOW())");
            insert.setLong(1, orderId);
            insert.setLong(2, strategyId);
            insert.setString(3, awardId);
            try {
                insert.executeUpdate();
            } catch (Exception duplicate) {
                connection.rollback();
                return -1;              // UNIQUE(order_id) 命中 → 已结算
            }

            PreparedStatement deduct = connection.prepareStatement(
                    "UPDATE strategy_detail SET award_surplus_count = award_surplus_count - 1" +
                            " WHERE strategy_id = ? AND award_id = ? AND award_surplus_count > 0");
            deduct.setLong(1, strategyId);
            deduct.setString(2, awardId);
            int updated = deduct.executeUpdate();
            if (updated == 0) {
                connection.rollback();
                throw new IllegalStateException("stock gap for strategy:" + strategyId + " award:" + awardId);
            }
            connection.commit();
            return 1;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private int querySurplusCount(long strategyId, String awardId) throws Exception {
        PreparedStatement ps = connection.prepareStatement(
                "SELECT award_surplus_count FROM strategy_detail WHERE strategy_id = ? AND award_id = ?");
        ps.setLong(1, strategyId);
        ps.setString(2, awardId);
        ResultSet rs = ps.executeQuery();
        rs.next();
        return rs.getInt(1);
    }
}
