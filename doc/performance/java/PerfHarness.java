import cn.bugstack.middleware.db.router.DBContextHolder;
import cn.bugstack.middleware.db.router.strategy.IDBRouterStrategy;
import com.anontokyo.lottery.LotteryApplication;
import com.anontokyo.lottery.application.worker.LotteryXxlJob;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.aggregates.ActivityConfigRich;
import com.anontokyo.lottery.domain.activity.model.req.ActivityConfigReq;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.vo.*;
import com.anontokyo.lottery.domain.activity.service.deploy.IActivityDeploy;
import com.anontokyo.lottery.domain.activity.service.lifecycle.ActivityLifecycleService;
import com.anontokyo.lottery.domain.activity.service.partake.IActivityPartake;
import com.xxl.job.core.context.XxlJobContext;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.web.bind.annotation.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;
import java.lang.management.*;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import javax.sql.DataSource;

/** 独立测试启动器：所有抽奖、活动启动和消息处理均复用生产组件。 */
@RestController
public class PerfHarness {
    private static ConfigurableApplicationContext context;
    private static final List<HikariDataSource> pools = new ArrayList<>();
    private static final Map<String, PoolTiming> timings = new LinkedHashMap<>();
    private static final LongAdder jobCalls = new LongAdder();
    private static final LongAdder jobNanos = new LongAdder();
    private static volatile Throwable jobFailure;
    private static AdminClient admin;

    /** Hikari 原生计时回调只做累加，避免采样线程改变连接获取行为。 */
    static class PoolTiming implements IMetricsTracker {
        final LongAdder count = new LongAdder();
        final LongAdder nanos = new LongAdder();
        final LongAdder timeouts = new LongAdder();
        @Override public void recordConnectionAcquiredNanos(long elapsed) { count.increment(); nanos.add(elapsed); }
        @Override public void recordConnectionTimeout() { timeouts.increment(); }
    }

    public static void main(String[] args) {
        try { launch(args); } catch (Throwable failure) {
            // Spring 启动失败必须退出整个测试进程，避免消费者继续抢占分区。
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void launch(String[] args) throws Exception {
        // 主应用与测试控制器使用同一真实 Spring 上下文，不替换业务组件。
        context = SpringApplication.run(new Class<?>[]{LotteryApplication.class, PerfHarness.class}, args);
        // 读取路由数据源实际物理连接池，包含全局库和两个用户分库。
        AbstractRoutingDataSource routing = (AbstractRoutingDataSource) context.getBean("mysqlDataSource", DataSource.class);
        for (String fieldName : Arrays.asList("resolvedDataSources", "resolvedDefaultDataSource")) {
            Field field = AbstractRoutingDataSource.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(routing);
            Collection<?> sources = value instanceof Map ? ((Map<?, ?>) value).values() : Collections.singleton(value);
            for (Object source : sources) {
                HikariDataSource pool = (HikariDataSource) source;
                // 连接池名称在首次初始化时确定，先建立连接再安装计时器和索引。
                try (Connection ignored = pool.getConnection()) { }
                pools.add(pool);
                PoolTiming timing = new PoolTiming();
                timings.put(pool.getPoolName(), timing);
                pool.setMetricsTrackerFactory((name, stats) -> timing);
            }
        }
        admin = AdminClient.create(Collections.singletonMap("bootstrap.servers", "127.0.0.1:29092"));
        // 固定一秒延迟触发已有 handler；任务越慢，实际调度周期越长，不能虚构吞吐。
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            long start = System.nanoTime();
            try {
                // XXL 参数是线程上下文，必须在执行任务的线程设置分库列表。
                XxlJobContext.setXxlJobContext(new XxlJobContext(1, "1,2", null, 0, 1));
                LotteryXxlJob job = context.getBean(LotteryXxlJob.class);
                job.awardStockSettlementOutboxJobHandler();
                job.lotteryOrderMQStateJobHandler();
                jobCalls.increment();
            } catch (Throwable failure) {
                // 采样会显式报告异常，同时输出原始堆栈，不把任务失败视为正常完成。
                jobFailure = failure;
                failure.printStackTrace();
                throw new IllegalStateException("压测异步任务失败", failure);
            } finally {
                jobNanos.add(System.nanoTime() - start);
                XxlJobContext.setXxlJobContext(null);
            }
        }, 1, 1, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { scheduler.shutdownNow(); admin.close(); }));
    }

    @PostMapping("/__perf/setup")
    public Map<String, Object> setup(@RequestParam("id") long id, @RequestParam("stock") int stock,
            @RequestParam("quota") int quota, @RequestParam("users") int users, @RequestParam("prefix") String prefix,
            @RequestParam(value="awardStock", defaultValue="-1") int awardStock) throws Exception {
        ActivityVO activity = new ActivityVO();
        activity.setActivityId(id);
        activity.setActivityName("隔离压测活动" + id);
        activity.setActivityDesc("独立环境专用，真实业务组件");
        activity.setBeginDateTime(new java.util.Date(System.currentTimeMillis() - 60000));
        activity.setEndDateTime(new java.util.Date(System.currentTimeMillis() + 86400000));
        activity.setStockCount(stock);
        activity.setTakeCount(quota);
        activity.setState(4);
        activity.setCreator("performance");
        StrategyVO strategy = new StrategyVO();
        strategy.setStrategyId(id);
        strategy.setStrategyMode(1);
        strategy.setGrantType(1);
        List<StrategyDetailVO> details = new ArrayList<>();
        List<AwardVO> awards = new ArrayList<>();
        // 五种等概率奖品，使用已有模拟券 handler，所有请求均走中奖订单完整主链路。
        for (int i = 0; i < 5; i++) {
            StrategyDetailVO detail = new StrategyDetailVO();
            detail.setAwardId("p" + id + "_" + i);
            detail.setAwardName("模拟券" + i);
            detail.setAwardCount(awardStock < 0 ? stock : awardStock);
            detail.setAwardRate(new BigDecimal("0.20"));
            details.add(detail);
            AwardVO award = new AwardVO();
            award.setAwardId(detail.getAwardId());
            award.setAwardName(detail.getAwardName());
            award.setAwardType(3);
            award.setAwardContent("项目现有本地模拟发奖");
            awards.add(award);
        }
        strategy.setStrategyDetailList(details);
        // 创建聚合时生产服务绑定策略并初始化剩余库存，禁止直接把数据库设为运行态。
        context.getBean(IActivityDeploy.class).createActivity(new ActivityConfigReq(id, new ActivityConfigRich(activity, strategy, awards)));
        // PASS->DOING 在生产行锁事务内预热 Redis，再提交运行状态。
        if (!context.getBean(ActivityLifecycleService.class).transition(id, Constants.ActivityState.PASS, Constants.ActivityState.DOING)) {
            throw new IllegalStateException("专用活动启动失败 " + id);
        }
        IDBRouterStrategy router = context.getBean(IDBRouterStrategy.class);
        Map<String, Integer> distribution = new TreeMap<>();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            Map<String, PreparedStatement> inserts = new HashMap<>();
            try {
                for (int i = 0; i < users; i++) {
                    String user = prefix + String.format("%07d", i);
                    // 路由调用生产策略，避免自行复制算法或往两个库重复放用户。
                    router.doRouter(user);
                    String db = DBContextHolder.getDBKey();
                    String location = db + "/" + DBContextHolder.getTBKey();
                    distribution.put(location, distribution.getOrDefault(location, 0) + 1);
                    router.clear();
                    PreparedStatement insert = inserts.get(db);
                    if (insert == null) {
                        insert = connection.prepareStatement("INSERT INTO lottery_" + db + ".user_take_activity_count(u_id,activity_id,total_count,left_count) VALUES (?,?,?,?)");
                        inserts.put(db, insert);
                    }
                    insert.setString(1, user); insert.setLong(2, id); insert.setInt(3, quota); insert.setInt(4, quota);
                    insert.addBatch();
                    // 控制初始化批次内存，不改变压测请求的业务语义。
                    if (i % 2000 == 1999) { for (PreparedStatement statement : inserts.values()) statement.executeBatch(); connection.commit(); }
                }
                for (PreparedStatement statement : inserts.values()) statement.executeBatch();
                connection.commit();
            } finally {
                router.clear();
                for (PreparedStatement statement : inserts.values()) statement.close();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activityId", id); result.put("users", users); result.put("shards", distribution);
        return result;
    }

    @PostMapping("/__perf/pending")
    public Object pending(@RequestParam("id") long id, @RequestParam("user") String user, @RequestParam("request") String request) {
        // 单独创建合法未消费参与记录，用于验证新请求 D007 与原请求恢复，非修改生产抽奖逻辑。
        return context.getBean(IActivityPartake.class).doPartake(new PartakeReq(user, id, request));
    }

    @GetMapping("/__perf/metrics")
    public Map<String, Object> metrics() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("time", System.currentTimeMillis());
        com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        result.put("processCpuTimeNs", os.getProcessCpuTime());
        result.put("heapUsed", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        result.put("heapMax", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax());
        result.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        List<Object> gc = new ArrayList<>();
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            gc.add(Arrays.asList(collector.getName(), collector.getCollectionCount(), collector.getCollectionTime()));
        }
        result.put("gc", gc);
        Map<String, Object> poolStats = new LinkedHashMap<>();
        for (HikariDataSource pool : pools) {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("url", pool.getJdbcUrl()); stats.put("max", pool.getMaximumPoolSize());
            stats.put("active", pool.getHikariPoolMXBean().getActiveConnections());
            stats.put("idle", pool.getHikariPoolMXBean().getIdleConnections());
            stats.put("waiting", pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
            PoolTiming timing = timings.get(pool.getPoolName());
            stats.put("acquires", timing.count.sum()); stats.put("acquireNanos", timing.nanos.sum()); stats.put("timeouts", timing.timeouts.sum());
            poolStats.put(pool.getPoolName(), stats);
        }
        result.put("pools", poolStats);
        result.put("jobCalls", jobCalls.sum()); result.put("jobNanos", jobNanos.sum());
        result.put("jobFailure", jobFailure == null ? null : jobFailure.toString());
        Map<String, Object> tomcat = new LinkedHashMap<>();
        javax.management.MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        for (javax.management.ObjectName name : server.queryNames(new javax.management.ObjectName("*:type=ThreadPool,*"), null)) {
            tomcat.put(name.toString(), Arrays.asList(server.getAttribute(name, "currentThreadsBusy"), server.getAttribute(name, "currentThreadCount"), server.getAttribute(name, "maxThreads")));
        }
        result.put("tomcat", tomcat);
        return result;
    }

    @GetMapping("/__perf/lag")
    public Map<String, Object> lag() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String group : Arrays.asList("lottery-perf-stock", "lottery-perf-invoice")) {
            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> requests = new HashMap<>();
            for (TopicPartition partition : offsets.keySet()) requests.put(partition, OffsetSpec.latest());
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = admin.listOffsets(requests).all().get(5, TimeUnit.SECONDS);
            long committed = 0, lag = 0;
            for (TopicPartition partition : offsets.keySet()) {
                committed += offsets.get(partition).offset();
                lag += ends.get(partition).offset() - offsets.get(partition).offset();
            }
            result.put(group, Arrays.asList(committed, lag, offsets.size()));
        }
        return result;
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://127.0.0.1:13329/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true", "root", "perf-only");
    }
}
