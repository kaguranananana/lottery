import com.anontokyo.lottery.LotteryApplication;
import com.anontokyo.lottery.interfaces.web.DrawAdmission;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import javax.servlet.Filter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import com.anontokyo.lottery.application.mq.producer.OutboxDispatcher;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.activity.model.aggregates.ActivityConfigRich;
import com.anontokyo.lottery.domain.activity.model.req.ActivityConfigReq;
import com.anontokyo.lottery.domain.activity.model.req.PartakeReq;
import com.anontokyo.lottery.domain.activity.model.vo.*;
import com.anontokyo.lottery.domain.activity.service.deploy.IActivityDeploy;
import com.anontokyo.lottery.domain.activity.service.lifecycle.ActivityLifecycleService;
import com.anontokyo.lottery.domain.activity.service.partake.IActivityPartake;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.*;
import com.anontokyo.lottery.application.mq.producer.RocketMqProducer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import java.lang.management.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/** 独立测试启动器：所有抽奖、活动启动和消息处理均复用生产组件。 */
@RestController
public class PerfHarness {
    private static ConfigurableApplicationContext context;
    private static final List<HikariDataSource> pools = new ArrayList<>();
    private static final Map<String, PoolTiming> timings = new LinkedHashMap<>();
    private static DefaultMQProducer admin;

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
        SpringApplication application = new SpringApplication(LotteryApplication.class, PerfHarness.class, PerfTiming.class);
        application.addInitializers(applicationContext -> {
            if (!"127.0.0.1".equals(applicationContext.getEnvironment().getProperty("server.address"))) {
                throw new IllegalStateException("压测控制面与合成身份只允许监听 127.0.0.1");
            }
        });
        context = application.run(args);
        // 从真实生产单数据源采集 Hikari 指标。
        HikariDataSource pool = context.getBean(HikariDataSource.class);
        try (Connection ignored = pool.getConnection()) { }
        pools.add(pool);
        PoolTiming timing = new PoolTiming();
        timings.put(pool.getPoolName(), timing);
        pool.setMetricsTrackerFactory((name, stats) -> timing);
        admin = new DefaultMQProducer("lottery-perf-inspect");
        admin.setNamesrvAddr(context.getEnvironment().getRequiredProperty("lottery.rocketmq.nameserver"));
        admin.start();
        Runtime.getRuntime().addShutdownHook(new Thread(admin::shutdown));
    }

    @Bean
    public FilterRegistrationBean<Filter> perfIdentity() {
        // 仅独立 loopback 压测进程装配，模拟已经认证的 Servlet Principal。
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter((request, response, chain) -> {
            HttpServletRequest http = (HttpServletRequest) request;
            String user = http.getHeader("X-Perf-Principal");
            chain.doFilter(new HttpServletRequestWrapper(http) {
                @Override public java.security.Principal getUserPrincipal() {
                    return user == null ? super.getUserPrincipal() : () -> user;
                }
            }, response);
        });
        registration.addUrlPatterns("/api/v1/draw");
        return registration;
    }

    @PostMapping("/__perf/setup")
    public Map<String, Object> setup(@RequestParam("id") long id, @RequestParam("stock") int stock,
            @RequestParam("quota") int quota, @RequestParam("users") int users, @RequestParam("prefix") String prefix,
            @RequestParam(value="awardStock", defaultValue="-1") int awardStock,
            @RequestParam(value="name", required=false) String name,
            @RequestParam(value="description", required=false) String description,
            @RequestParam(value="awardRate", defaultValue="0.20") BigDecimal awardRate,
            @RequestParam(value="awardKinds", defaultValue="5") int awardKinds,
            @RequestParam(value="start", defaultValue="true") boolean start) throws Exception {
        ActivityVO activity = new ActivityVO();
        activity.setActivityId(id);
        // 网页演示可以设置活动文案；原压测调用继续使用既定名称和描述。
        activity.setActivityName(name == null ? "隔离压测活动" + id : name);
        activity.setActivityDesc(description == null ? "独立环境专用，真实业务组件" : description);
        activity.setBeginDateTime(new java.util.Date(System.currentTimeMillis() - 60000));
        activity.setEndDateTime(new java.util.Date(System.currentTimeMillis() + 86400000));
        activity.setTakeCount(quota);
        activity.setState(4);
        activity.setCreator("performance");
        List<ActivityPrizeVO> details = new ArrayList<>();
        List<AwardVO> awards = new ArrayList<>();
        // stock 仅作为每种奖品的默认总量；活动只有个人次数限制，没有总库存。
        // 五种等概率模拟券，压测默认总概率 100%；演示可留出未中奖概率。
        for (int i = 0; i < awardKinds; i++) {
            ActivityPrizeVO detail = new ActivityPrizeVO();
            detail.setAwardId("p" + id + "_" + i);
            detail.setAwardCount(awardStock < 0 ? stock : awardStock);
            detail.setAwardRate(awardRate);
            details.add(detail);
            AwardVO award = new AwardVO();
            award.setAwardId(detail.getAwardId());
            award.setAwardName("模拟券" + i);
            award.setAwardType(3);
            award.setAwardContent("项目现有本地模拟发奖");
            awards.add(award);
        }
        // 创建聚合时生产服务绑定活动奖品并初始化剩余库存，禁止直接把数据库设为运行态。
        context.getBean(IActivityDeploy.class).createActivity(new ActivityConfigReq(id, new ActivityConfigRich(activity, details, awards)));
        // READY→RUNNING 在生产行锁事务内预热 Redis，再提交运行状态。
        if (start && !context.getBean(ActivityLifecycleService.class).transition(id, Constants.ActivityState.READY, Constants.ActivityState.RUNNING)) {
            throw new IllegalStateException("专用活动启动失败 " + id);
        }
        // 预置足量用户，压测仍执行真实 TX1 查询与扣减。
        String insertQuota = "INSERT INTO user_activity_quota(u_id,activity_id,total_count,left_count) VALUES (?,?,?,?)";
        try (Connection connection = connection(); PreparedStatement insert = connection.prepareStatement(insertQuota)) {
            connection.setAutoCommit(false);
            for (int i = 0; i < users; i++) {
                insert.setString(1, prefix + String.format("%07d", i));
                insert.setLong(2, id);
                insert.setInt(3, quota);
                insert.setInt(4, quota);
                insert.addBatch();
                if (i % 2000 == 1999) { insert.executeBatch(); connection.commit(); }
            }
            insert.executeBatch();
            connection.commit();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activityId", id);
        result.put("users", users);
        return result;
    }

    @PostMapping("/__perf/pending")
    public Object pending(@RequestParam("id") long id, @RequestParam("user") String user, @RequestParam("request") String request) {
        // 单独创建合法未消费参与记录，用于验证新请求 D007 与原请求恢复，非修改生产抽奖逻辑。
        return context.getBean(IActivityPartake.class).doPartake(new PartakeReq(user, id, request));
    }

    @PostMapping("/__perf/replay")
    public void replay(@RequestParam("activityId") long activityId, @RequestParam("user") String user,
            @RequestParam("order") long order, @RequestParam("award") String award) throws Exception {
        com.anontokyo.lottery.domain.award.service.order.IAwardOrder orders = context.getBean(com.anontokyo.lottery.domain.award.service.order.IAwardOrder.class);
        com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent invoice = orders.queryFulfillmentEventByOrderId(user, order);
        // 只重投已有订单；测试控制面不创建订单，也不修改库存或完成状态。
        if (invoice == null) throw new IllegalArgumentException("重投订单不存在 " + order);
        com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO stock = new com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO();
        stock.setTakeId(invoice.getTakeId()); stock.setuId(user); stock.setOrderId(order); stock.setActivityId(activityId); stock.setAwardId(award);
        RocketMqProducer producer = context.getBean(RocketMqProducer.class);
        // 两次实际 broker 确认后返回，消费者仍由真实 RocketMQ 异步驱动。
        for (int i = 0; i < 2; i++) {
            producer.sendStockSettlement(stock);
            producer.sendAwardFulfillment(invoice);
        }
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
        result.put("admission", context.getBean(DrawAdmission.class).metrics());
        result.put("outbox", context.getBean(OutboxDispatcher.class).metrics());
        result.putAll(PerfTiming.scheduling());
        // 可选诊断仅在测试启动器中提供，业务阶段与 SQL 计时均为累计值。
        result.put("timing", PerfTiming.snapshot());
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
        result.put("lottery-perf-stock", queueLag("lottery-perf-stock", RocketMqProducer.STOCK_TOPIC));
        result.put("lottery-perf-invoice", queueLag("lottery-perf-invoice", RocketMqProducer.FULFILLMENT_TOPIC));
        return result;
    }

    private List<Long> queueLag(String group, String topic) throws Exception {
        // 无消费的队列位点为零。直接读实际队列末尾与消费位点，统计总积压。
        long committed = 0;
        long lag = 0;
        java.util.List<org.apache.rocketmq.common.message.MessageQueue> queues = admin.fetchPublishMessageQueues(topic);
        for (org.apache.rocketmq.common.message.MessageQueue queue : queues) {
            long end = admin.getDefaultMQProducerImpl().getmQClientFactory().getMQAdminImpl().maxOffset(queue);
            if (end == 0) {
                continue;
            }
            org.apache.rocketmq.remoting.protocol.header.QueryConsumerOffsetRequestHeader request =
                    new org.apache.rocketmq.remoting.protocol.header.QueryConsumerOffsetRequestHeader();
            request.setTopic(topic);
            request.setConsumerGroup(group);
            request.setQueueId(queue.getQueueId());
            long consumed;
            try {
                consumed = admin.getDefaultMQProducerImpl().getmQClientFactory().getMQClientAPIImpl()
                        .queryConsumerOffset("127.0.0.1:20911", request, 3000);
            } catch (org.apache.rocketmq.client.exception.MQBrokerException e) {
                if (e.getResponseCode() != org.apache.rocketmq.remoting.protocol.ResponseCode.QUERY_NOT_FOUND) {
                    throw e;
                }
                consumed = 0;
            }
            committed += consumed;
            lag += end - consumed;
        }
        return Arrays.asList(committed, lag, (long) queues.size());
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://127.0.0.1:13349/lottery?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true", "root", "perf-only");
    }
}
