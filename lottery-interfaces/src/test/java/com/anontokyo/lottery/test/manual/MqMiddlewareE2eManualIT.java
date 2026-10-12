package com.anontokyo.lottery.test.manual;

import cn.bugstack.middleware.db.router.DBContextHolder;
import cn.bugstack.middleware.db.router.strategy.IDBRouterStrategy;
import com.anontokyo.lottery.LotteryApplication;
import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
import com.anontokyo.lottery.application.worker.LotteryXxlJob;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.strategy.repository.IStrategyRepository;
import com.anontokyo.lottery.infrastructure.dao.IUserStrategyExportDao;
import com.anontokyo.lottery.infrastructure.po.UserStrategyExport;
import com.xxl.job.core.context.XxlJobContext;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.*;
import org.junit.runner.RunWith;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * Docker 端到端验证：真实 Spring 组件、Kafka broker/消费者、Redis、MySQL 两库八表及库存事务。
 * 只延迟生产者返回的 Future 通知，消息仍由生产 KafkaProducer 真实发送；失败通知为显式故障注入。
 * 从持久化订单 Outbox 开始，不覆盖 HTTP 抽奖入口、外部发奖渠道或 XXL-Job admin 调度。
 */
@RunWith(SpringRunner.class)
@ActiveProfiles("mq-e2e")
@SpringBootTest(classes = {LotteryApplication.class, MqMiddlewareE2eManualIT.CallbackHooks.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class MqMiddlewareE2eManualIT {
    private static final String MYSQL = "jdbc:mysql://127.0.0.1:13309/";
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis());
    private static final String STOCK_TOPIC = "lottery_strategy_award_stock";
    private static final String INVOICE_TOPIC = "lottery_invoice";

    @Autowired private IUserStrategyExportDao orderDao;
    @Autowired private IOrderRepository orders;
    @Autowired private IStrategyRepository strategies;
    @Autowired private IDBRouterStrategy router;
    @Autowired private LotteryXxlJob job;
    @Autowired private KafkaProducer producer;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private StringRedisTemplate redis;
    @Value("${lottery.kafka.consumer.invoice-group-id}") private String invoiceGroup;
    @Value("${lottery.kafka.consumer.strategy-award-stock-group-id}") private String stockGroup;

    private long strategyId;
    private CallbackProducer callbacks;
    private AdminClient admin;

    @BeforeClass
    public static void createSchema() throws Exception {
        // 运行标识固定后，两个真实消费者组在本轮测试内保持一致且不沿用旧消费位点。
        System.setProperty("lottery.e2e.run", String.valueOf(IDS.get()));
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            for (String schema : Arrays.asList("lottery", "lottery_01", "lottery_02")) {
                // 13309 是测试 compose 的专用 MySQL；只在这个隔离实例重建 fixture。
                statement.execute("DROP DATABASE IF EXISTS " + schema);
                statement.execute("CREATE DATABASE " + schema);
                statement.execute("USE " + schema);
                String baseline = schema.equals("lottery") ? "lottery.sql" : "lottery_01.sql";
                createBaselineTables(statement, "../doc/sql/" + baseline);
                if (!schema.equals("lottery")) {
                    for (int table = 0; table < 4; table++) {
                        // 当前订单 Mapper 需要 take_id，旧基线尚未包含该列。
                        statement.execute("ALTER TABLE user_strategy_export_" + String.format("%03d", table)
                                + " ADD take_id BIGINT NULL");
                    }
                }
            }
            // 执行交付的库存 Outbox 字段/索引与全局账本迁移，不在测试里重写其结构。
            executeSqlFile(statement, "../doc/sql/20260315_async_award_stock_outbox.sql");
            executeSqlFile(statement, "../docs/sql/V2__architecture_simplification.sql");
        }
    }

    @Before
    public void startPipeline() throws Exception {
        strategyId = IDS.incrementAndGet();
        // 本用例独立库存，MySQL 与 Redis 初始均为 100，真实 Redis 预扣后再异步结算。
        sql("INSERT INTO lottery.strategy_detail (strategy_id,award_id,award_name,award_count,award_surplus_count,award_rate)"
                + " VALUES (?, 'C', '端到端测试券', 100, 100, 1.00)", strategyId);
        redis.opsForValue().set(Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_COUNT(strategyId, "C"), "100");
        callbacks = (CallbackProducer) producer;
        admin = AdminClient.create(Collections.singletonMap("bootstrap.servers", "127.0.0.1:19092"));
        listeners.start();
        // 真实分区分配完成后发送，避免把监听器尚未就绪当成业务失败。
        await("消费者分区分配", () -> listeners.getListenerContainers().stream()
                .allMatch(container -> container.getAssignedPartitions() != null && !container.getAssignedPartitions().isEmpty()));
        XxlJobContext.setXxlJobContext(new XxlJobContext(1, "1,2", null, 0, 1));
    }

    @After
    public void closeTestContext() {
        // 即使用例失败，也恢复监听器，防止暂停状态影响后续用例。
        listeners.start();
        XxlJobContext.setXxlJobContext(null);
        if (admin != null) {
            admin.close();
        }
    }

    @Test
    public void completePipelineRoutesAcrossAllEightOrderTables() throws Exception {
        Set<String> visited = new HashSet<>();
        List<UserStrategyExport> rows = new ArrayList<>();
        while (visited.size() < 8) {
            String user = "e2e" + IDS.incrementAndGet();
            String physicalTable = physicalTable(user);
            if (visited.add(physicalTable)) {
                rows.add(createOrder(user, 0, 0));
            }
        }
        // 真实分片扫描、Redis dispatch marker、Kafka、结算事务、发奖消费者连续执行。
        job.awardStockSettlementOutboxJobHandler();
        for (UserStrategyExport row : rows) {
            awaitCompleted(row);
            assertEquals(1, ledgerCount(row));
            await("发送回调清理 dispatch marker", () -> !redis.hasKey(Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_DISPATCHING(row.getOrderId())));
            assertFalse(redis.hasKey(Constants.RedisKey.KEY_LOTTERY_INVOICE_MQ_PROCESSING(row.getOrderId())));
        }
        assertEquals(92, dbStock());
        assertEquals("92", redis.opsForValue().get(Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_COUNT(strategyId, "C")));
        awaitCommitted();
    }

    @Test
    public void consumersFinishBeforeDelayedSuccessCallbacks() throws Exception {
        UserStrategyExport row = createOrder(null, 0, 0);
        Gate stock = callbacks.hold(STOCK_TOPIC, row.getOrderId());
        Gate invoice = callbacks.hold(INVOICE_TOPIC, row.getOrderId());
        job.awardStockSettlementOutboxJobHandler();
        Attempt stockSend = stock.attempt(0);
        Attempt invoiceSend = invoice.attempt(0);
        // Broker 已确认消息，两条真实消费者均已完成，但调度任务的发送通知仍未执行。
        awaitCompleted(row);
        assertTrue(redis.hasKey(Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_DISPATCHING(row.getOrderId())));
        Date completedAt = query(row).getUpdateTime();
        stockSend.success();
        invoiceSend.success();
        assertCompleted(row);
        assertEquals(completedAt, query(row).getUpdateTime());
        await("dispatch marker 清理", () -> !redis.hasKey(Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_DISPATCHING(row.getOrderId())));
        awaitCommitted();
    }

    @Test
    public void consumersFinishBeforeInjectedLateFailureCallbacks() throws Exception {
        UserStrategyExport row = createOrder(null, 0, 0);
        Gate stock = callbacks.hold(STOCK_TOPIC, row.getOrderId());
        Gate invoice = callbacks.hold(INVOICE_TOPIC, row.getOrderId());
        job.awardStockSettlementOutboxJobHandler();
        Attempt stockSend = stock.attempt(0);
        Attempt invoiceSend = invoice.attempt(0);
        awaitCompleted(row);
        Date completedAt = query(row).getUpdateTime();
        // 真实 broker 发送成功后注入迟到的失败通知，专门验证业务回调的状态保护。
        stockSend.failure();
        invoiceSend.failure();
        assertCompleted(row);
        assertEquals(completedAt, query(row).getUpdateTime());
        assertEquals(1, ledgerCount(row));
        assertEquals(99, dbStock());
        awaitCommitted();
    }

    @Test
    public void realRedisExpiryAndOutOfOrderRetryCallbacksRemainSafe() throws Exception {
        topicContainer(STOCK_TOPIC).stop();
        UserStrategyExport row = createOrder(null, 0, 0);
        Gate gate = callbacks.hold(STOCK_TOPIC, row.getOrderId());
        job.awardStockSettlementOutboxJobHandler();
        gate.attempt(0).failure();
        assertEquals(Integer.valueOf(2), query(row).getStockMqState());
        // 首次 FAIL 必须被生产补偿扫描重新投递。
        job.awardStockSettlementOutboxJobHandler();
        gate.attempt(1);
        for (int index = 2; index < 4; index++) {
            // 真实 Redis marker 过期模拟异步回调长于锁窗口，允许后续 worker 再次投递。
            String marker = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_DISPATCHING(row.getOrderId());
            redis.expire(marker, 1, TimeUnit.SECONDS);
            await("Redis dispatch marker 过期", () -> !redis.hasKey(marker));
            job.awardStockSettlementOutboxJobHandler();
            gate.attempt(index);
        }
        // 最新成功先到，旧失败后到；恢复真实消费后，最旧的成功通知最后到。
        gate.attempt(3).success();
        assertEquals(Integer.valueOf(3), query(row).getStockMqState());
        gate.attempt(2).failure();
        assertEquals(Integer.valueOf(3), query(row).getStockMqState());
        topicContainer(STOCK_TOPIC).start();
        awaitCompleted(row);
        gate.attempt(1).success();
        assertCompleted(row);
        assertEquals(1, ledgerCount(row));
        assertEquals(99, dbStock());
        awaitCommitted();
    }

    @Test
    public void timedOutSentStockIsReallyResentAndConsumed() throws Exception {
        UserStrategyExport row = createOrder(null, 3, 0);
        age(row);
        // 不改生产超时阈值，直接将隔离 fixture 的数据库时间设置为已过期。
        job.awardStockSettlementOutboxJobHandler();
        awaitCompleted(row);
        assertEquals(1, ledgerCount(row));
        assertEquals(99, dbStock());
        awaitCommitted();
    }

    @Test
    public void invoiceCompensationRecoversFailSentAndLegacyComplete() throws Exception {
        for (int state : Arrays.asList(2, 3, 1)) {
            UserStrategyExport row = createOrder(null, 0, state);
            // 库存已结算、发奖未完成的恢复窗口，用真实库存 Repository 建立事实。
            assertTrue(strategies.settleAwardStock(stock(row)));
            orders.updateStockMqState(row.getuId(), row.getOrderId(), 1);
            age(row);
            Gate gate = callbacks.hold(INVOICE_TOPIC, row.getOrderId());
            job.lotteryOrderMQStateJobHandler();
            Attempt sent = gate.attempt(0);
            awaitCompleted(row);
            // 发奖已完成后才执行补偿发送成功回调，终态必须保持。
            sent.success();
            assertCompleted(row);
            assertEquals(1, ledgerCount(row));
        }
        assertEquals(97, dbStock());
        awaitCommitted();
    }

    @Test
    public void duplicateBrokerMessagesDoNotRepeatInventoryOrGrant() throws Exception {
        UserStrategyExport row = createOrder(null, 0, 0);
        job.awardStockSettlementOutboxJobHandler();
        awaitCompleted(row);
        Date grantedAt = query(row).getGrantDate();
        InvoiceVO invoice = orders.queryInvoiceByOrderId(row.getuId(), row.getOrderId());
        for (int index = 0; index < 3; index++) {
            // 重复消息也真实进入 broker 和消费者，不直接调用消费方法。
            producer.sendStrategyAwardStock(stock(row)).get(30, TimeUnit.SECONDS);
            producer.sendLotteryInvoice(invoice).get(30, TimeUnit.SECONDS);
        }
        awaitCommitted();
        assertCompleted(row);
        assertEquals(1, ledgerCount(row));
        assertEquals(99, dbStock());
        assertEquals(grantedAt, query(row).getGrantDate());
    }

    @Test
    public void actualBrokerFailureIsRetriedAfterKafkaRestarts() throws Exception {
        topicContainer(STOCK_TOPIC).stop();
        UserStrategyExport row = createOrder(null, 0, 0);
        // 先真实发送一条消息来取得分区元数据，消费者暂停使订单仍处于 INIT。
        producer.sendStrategyAwardStock(stock(row)).get(30, TimeUnit.SECONDS);
        int acceptedSends = callbacks.realSends.size();
        try {
            compose("stop", "kafka");
            job.awardStockSettlementOutboxJobHandler();
            // Kafka 确实不可用，真实 Future 超时经生产失败回调落 FAIL。
            await("真实 broker 停机后的 FAIL", () -> query(row).getStockMqState() == 2);
            assertEquals(acceptedSends + 1, callbacks.realSends.size());
            ListenableFuture<SendResult<String, Object>> failed = callbacks.realSends.remove(acceptedSends);
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> failed.get(30, TimeUnit.SECONDS));
            // KafkaTemplate 的 Future 用 KafkaProducerException 包装原始 broker 超时，检查完整 cause 链。
            assertTrue(rejected.getCause() instanceof org.springframework.kafka.core.KafkaProducerException);
            assertTrue(rejected.getCause().getCause() instanceof org.apache.kafka.common.errors.TimeoutException);
            await("真实发送失败清理 marker", () -> !redis.hasKey(Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_DISPATCHING(row.getOrderId())));
        } finally {
            // 用真实容器健康检查等待 broker 恢复，不将恢复前的连接失败当成补偿结果。
            compose("up", "-d", "--wait", "kafka");
        }
        job.awardStockSettlementOutboxJobHandler();
        await("恢复后补偿发送成功", () -> query(row).getStockMqState() == 3);
        topicContainer(STOCK_TOPIC).start();
        awaitCompleted(row);
        assertEquals(1, ledgerCount(row));
        assertEquals(99, dbStock());
        awaitCommitted();
    }

    private static void compose(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList("docker", "compose", "-f",
                "src/test/resources/mq-e2e/docker-compose.yml"));
        command.addAll(Arrays.asList(arguments));
        // 生命周期命令仅操作本测试 compose，输出保留在测试日志中。
        Process process = new ProcessBuilder(command).inheritIO().start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue("测试容器操作超时：" + command, finished);
        assertEquals("测试容器操作失败：" + command, 0, process.exitValue());
    }

    private UserStrategyExport createOrder(String user, int stockState, int invoiceState) {
        long id = IDS.incrementAndGet();
        UserStrategyExport row = new UserStrategyExport();
        row.setuId(user == null ? "e2e" + id : user);
        row.setOrderId(id);
        row.setUuid(String.valueOf(id));
        row.setActivityId(strategyId);
        row.setStrategyId(strategyId);
        row.setStrategyMode(1);
        row.setGrantType(1);
        row.setGrantState(0);
        row.setAwardId("C");
        row.setAwardType(3);
        row.setAwardName("端到端测试券");
        row.setAwardContent("本地模拟发奖");
        row.setStockMqState(stockState);
        row.setMqState(invoiceState);
        // 真实 Redis 库存预扣，订单由生产 DAO 路由写入，之后完全走生产异步链路。
        assertTrue(strategies.deductStockByRedis(strategyId, "C").isSuccess());
        orderDao.insert(row);
        return row;
    }

    private UserStrategyExport query(UserStrategyExport row) {
        return orderDao.queryUserStrategyExportByUuid(row);
    }

    private void awaitCompleted(UserStrategyExport row) throws Exception {
        await("订单两条消息与发奖均完成 orderId=" + row.getOrderId(), () -> {
            UserStrategyExport current = query(row);
            return current.getStockMqState() == 1 && current.getMqState() == 1 && current.getGrantState() == 1;
        });
    }

    private void assertCompleted(UserStrategyExport row) {
        UserStrategyExport current = query(row);
        assertEquals(Integer.valueOf(1), current.getStockMqState());
        assertEquals(Integer.valueOf(1), current.getMqState());
        assertEquals(Integer.valueOf(1), current.getGrantState());
    }

    private String physicalTable(String user) {
        try {
            // 路由信息与动态 MyBatis 插件使用同一个生产上下文。
            router.doRouter(user);
            return "lottery_" + DBContextHolder.getDBKey() + ".user_strategy_export_" + DBContextHolder.getTBKey();
        } finally {
            router.clear();
        }
    }

    private void age(UserStrategyExport row) throws SQLException {
        sql("UPDATE " + physicalTable(row.getuId())
                + " SET update_time=DATE_SUB(NOW(),INTERVAL 11 MINUTE) WHERE order_id=?", row.getOrderId());
    }

    private int ledgerCount(UserStrategyExport row) throws SQLException {
        return number("SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE order_id=?", row.getOrderId());
    }

    private int dbStock() throws SQLException {
        return number("SELECT award_surplus_count FROM lottery.strategy_detail WHERE strategy_id=? AND award_id='C'", strategyId);
    }

    private AwardStockSettlementVO stock(UserStrategyExport row) {
        AwardStockSettlementVO stock = new AwardStockSettlementVO();
        stock.setuId(row.getuId());
        stock.setOrderId(row.getOrderId());
        stock.setStrategyId(strategyId);
        stock.setAwardId("C");
        return stock;
    }

    private MessageListenerContainer topicContainer(String topic) {
        return listeners.getListenerContainers().stream()
                .filter(container -> Arrays.asList(container.getContainerProperties().getTopics()).contains(topic))
                .findFirst().orElseThrow(() -> new IllegalStateException("监听器缺失：" + topic));
    }

    private void awaitCommitted() throws Exception {
        Map<TopicPartition, Long> expected = new HashMap<>();
        for (ListenableFuture<SendResult<String, Object>> future : callbacks.realSends) {
            // 真实 Kafka 元数据给出分区和位点，消费者提交位点必须越过每个已发送消息。
            RecordMetadata record = future.get(30, TimeUnit.SECONDS).getRecordMetadata();
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            expected.merge(partition, record.offset() + 1, Math::max);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
            offsets.putAll(admin.listConsumerGroupOffsets(stockGroup).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS));
            offsets.putAll(admin.listConsumerGroupOffsets(invoiceGroup).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS));
            if (expected.entrySet().stream().allMatch(entry -> offsets.containsKey(entry.getKey())
                    && offsets.get(entry.getKey()).offset() >= entry.getValue())) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Kafka 消费位点未提交至已发送消息之后：" + expected);
    }

    private static void await(String condition, BooleanSupplier probe) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (probe.getAsBoolean()) {
                return;
            }
            // 只等待真实异步事件；探测异常直接失败，不吞异常或重跑业务。
            Thread.sleep(50);
        }
        fail("等待超时：" + condition);
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(MYSQL + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", "root", "root");
    }

    private static void sql(String sql, Object... values) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private static int number(String sql, Object value) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static void createBaselineTables(Statement statement, String file) throws Exception {
        for (String sql : sqlStatements(file)) {
            // 只加载生产表定义，不加载旧业务种子或破坏其它实例数据。
            if (sql.startsWith("CREATE TABLE")) {
                statement.execute(sql);
            }
        }
    }

    private static void executeSqlFile(Statement statement, String file) throws Exception {
        for (String sql : sqlStatements(file)) {
            statement.execute(sql);
        }
    }

    private static List<String> sqlStatements(String file) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        List<String> statements = new ArrayList<>();
        // 生产列注释可能含分号，必须按 SQL 引号和注释语法切分，不能用正则或 String.split 截断。
        ScriptUtils.splitSqlScript(source, ";", statements);
        return statements;
    }

    /** 测试只装饰已经完成依赖注入的生产者，不替换 KafkaTemplate、DAO、Redis 或消费者。 */
    @TestConfiguration
    public static class CallbackHooks {
        @Bean
        public static BeanPostProcessor callbackGate() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
                    return name.equals("kafkaProducer") ? new CallbackProducer((KafkaProducer) bean) : bean;
                }
            };
        }
    }

    /** 两种发送都委托生产实现；未设置 gate 时直接返回真实 Future。 */
    public static class CallbackProducer extends KafkaProducer {
        private final KafkaProducer delegate;
        private final Map<String, Gate> gates = new ConcurrentHashMap<>();
        private final List<ListenableFuture<SendResult<String, Object>>> realSends = new CopyOnWriteArrayList<>();

        CallbackProducer(KafkaProducer delegate) {
            this.delegate = delegate;
        }

        Gate hold(String topic, Long orderId) {
            Gate gate = new Gate();
            gates.put(topic + ":" + orderId, gate);
            return gate;
        }

        @Override
        public ListenableFuture<SendResult<String, Object>> sendStrategyAwardStock(AwardStockSettlementVO stock) {
            return capture(STOCK_TOPIC, stock.getOrderId(), delegate.sendStrategyAwardStock(stock));
        }

        @Override
        public ListenableFuture<SendResult<String, Object>> sendLotteryInvoice(InvoiceVO invoice) {
            return capture(INVOICE_TOPIC, invoice.getOrderId(), delegate.sendLotteryInvoice(invoice));
        }

        private ListenableFuture<SendResult<String, Object>> capture(String topic, Long orderId,
                ListenableFuture<SendResult<String, Object>> real) {
            // 所有发送记录真实 broker 元数据，供最后检查消费提交位点。
            realSends.add(real);
            Gate gate = gates.get(topic + ":" + orderId);
            // gate 仅控制回调顺序；消息已经由生产 KafkaProducer 发送。
            return gate == null ? real : gate.capture(real);
        }
    }

    public static class Gate {
        private final List<Attempt> attempts = new CopyOnWriteArrayList<>();

        ListenableFuture<SendResult<String, Object>> capture(ListenableFuture<SendResult<String, Object>> real) {
            Attempt attempt = new Attempt(real);
            attempts.add(attempt);
            return attempt.notification;
        }

        Attempt attempt(int index) throws Exception {
            await("真实 Kafka 发送尝试 " + index, () -> attempts.size() > index);
            Attempt attempt = attempts.get(index);
            // 真实发送失败必须显式暴露，不能用测试生成的成功掩盖网络或 broker 故障。
            attempt.real.get(30, TimeUnit.SECONDS);
            return attempt;
        }
    }

    public static class Attempt {
        private final ListenableFuture<SendResult<String, Object>> real;
        private final SettableListenableFuture<SendResult<String, Object>> notification = new SettableListenableFuture<>();

        Attempt(ListenableFuture<SendResult<String, Object>> real) {
            this.real = real;
        }

        void success() throws Exception {
            // 原样转交真实发送结果，只将通知的执行时刻延后。
            notification.set(real.get(30, TimeUnit.SECONDS));
        }

        void failure() throws Exception {
            // 先证明 broker 已接受，再注入失败通知；报告中必须区分此场景与真实发送失败。
            real.get(30, TimeUnit.SECONDS);
            notification.setException(new IllegalStateException("测试注入：迟到发送失败通知"));
        }
    }
}
