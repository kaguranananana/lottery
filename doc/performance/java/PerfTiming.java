import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** 仅由独立压测启动器装配的累计计时，不改变生产方法、事务或异常传播。 */
@Configuration
public class PerfTiming {
    private static final Map<String, Timing> VALUES = new ConcurrentHashMap<>();
    // 仅测试线程内保存当前业务阶段，finally 恢复，避免线程池复用时标签串到别的请求。
    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    /** 固定桶记录近似分位数上界，避免逐请求存储造成额外内存压力。 */
    private static class Timing {
        final LongAdder calls = new LongAdder();
        final LongAdder nanos = new LongAdder();
        final LongAdder errors = new LongAdder();
        final LongAdder[] buckets = new LongAdder[20];
        Timing() { for (int i = 0; i < buckets.length; i++) buckets[i] = new LongAdder(); }
        void record(long elapsed, boolean failed) {
            calls.increment();
            nanos.add(elapsed);
            if (failed) errors.increment();
            // 桶上界从 1 微秒开始按二倍增长；最后一桶代表超过最大上界。
            long micros = Math.max(1, (elapsed + 999) / 1000);
            int index = Math.min(19, 64 - Long.numberOfLeadingZeros(micros - 1));
            buckets[index].increment();
        }
    }

    static void record(String key, long start, boolean failed) {
        VALUES.computeIfAbsent(key, ignored -> new Timing()).record(System.nanoTime() - start, failed);
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> result = new TreeMap<>();
        VALUES.forEach((key, timing) -> {
            List<Long> buckets = new ArrayList<>();
            for (LongAdder bucket : timing.buckets) buckets.add(bucket.sum());
            result.put(key, Arrays.asList(timing.calls.sum(), timing.nanos.sum(), timing.errors.sum(), buckets));
        });
        return result;
    }

    public static Map<String, Object> scheduling() {
        long calls = 0;
        long nanos = 0;
        long errors = 0;
        for (Map.Entry<String, Timing> entry : VALUES.entrySet()) {
            if (entry.getKey().startsWith("stage/LotteryScheduledTasks.")) {
                calls += entry.getValue().calls.sum();
                nanos += entry.getValue().nanos.sum();
                errors += entry.getValue().errors.sum();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobCalls", calls);
        result.put("jobNanos", nanos);
        result.put("jobFailure", errors == 0 ? null : "scheduled task errors: " + errors);
        return result;
    }

    @Bean public SqlTiming perfSqlTiming() { return new SqlTiming(); }
    @Bean public StageTiming perfStageTiming() { return new StageTiming(); }

    /** MyBatis 计时覆盖客户端执行与等待；包含事务内 SQL，不代表数据库 CPU。 */
    @Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
    })
    public static class SqlTiming implements Interceptor {
        @Override public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            // 按线程角色分别汇总后台和 HTTP SQL。
            String thread = Thread.currentThread().getName();
            String role = ROLE.get();
            if (role == null) role = thread.startsWith("http") ? "http" : "async";
            // 以事务连接的 catalog 记录实际使用的库。
            String schema = ((Executor) invocation.getTarget()).getTransaction().getConnection().getCatalog();
            String key = "sql/" + role + "/" + schema + "/" + statement.getId();
            long start = System.nanoTime();
            boolean failed = true;
            try {
                Object result = invocation.proceed();
                failed = false;
                return result;
            } finally {
                // 异常照常向上传播，仅在 finally 累加计数。
                record(key, start, failed);
            }
        }
        @Override public Object plugin(Object target) { return Plugin.wrap(target, this); }
        @Override public void setProperties(Properties properties) { }
    }

    /** 业务阶段为包含式计时，不能把嵌套方法耗时直接相加。 */
    @Aspect
    public static class StageTiming {
        @Around("execution(* com.anontokyo.lottery.infrastructure.repository.OrderRepository.saveDrawOrderWithTakeLock(..))")
        public Object holdAfterCommit(ProceedingJoinPoint call) throws Throwable {
            Object result = call.proceed();
            // 此刻订单已提交，事务消息仍是 Half。只在故障演练启动器里暂停。
            if (Boolean.getBoolean("lottery.perf.hold-after-commit")) {
                new java.util.concurrent.CountDownLatch(1).await();
            }
            return result;
        }

        @Around("execution(* com.anontokyo.lottery.infrastructure.utils.RedisStockReservations.reserve(..))")
        public Object holdAfterReserve(ProceedingJoinPoint call) throws Throwable {
            Object result = call.proceed();
            // 订单尚未 INSERT，持有 TX2 参与行锁。
            if (Boolean.getBoolean("lottery.perf.hold-after-reserve")) {
                new java.util.concurrent.CountDownLatch(1).await();
            }
            return result;
        }

        @Around("execution(* com.anontokyo.lottery.infrastructure.repository.DrawParticipationRepository.takeParticipation(..)) || "
                + "execution(* com.anontokyo.lottery.infrastructure.repository.OrderRepository.saveDrawOrderWithTakeLock(..)) || "
                + "execution(* com.anontokyo.lottery.infrastructure.repository.ActivityPrizeRepository.settleAwardStock(..)) || "
                + "execution(* com.anontokyo.lottery.infrastructure.repository.OrderRepository.scan*(..)) || "
                + "execution(* com.anontokyo.lottery.application.worker.LotteryScheduledTasks.*(..)) || "
                + "execution(* com.anontokyo.lottery.application.mq.producer.RocketMqProducer.place(..)) || "
                + "execution(* com.anontokyo.lottery.application.mq.consumer.*.onMessage(..))")
        public Object time(ProceedingJoinPoint call) throws Throwable {
            String previous = ROLE.get();
            Object[] arguments = call.getArgs();
            // 消费阶段按实际业务监听器区分。
            if (call.getSignature().getName().equals("onMessage")) {
                ROLE.set("consumer/" + call.getSignature().getDeclaringType().getSimpleName());
            } else if (call.getSignature().getDeclaringTypeName().endsWith("LotteryScheduledTasks")) {
                // 扫描和该任务线程上的逐条投递 SQL 使用同一个任务阶段标签。
                ROLE.set("job/" + call.getSignature().getName());
            }
            long start = System.nanoTime();
            boolean failed = true;
            try {
                Object result = call.proceed(arguments);
                failed = false;
                return result;
            } finally {
                record("stage/" + call.getSignature().toShortString(), start, failed);
                // 嵌套仓储调用恢复父阶段；最外层退出时清除线程标签。
                if (previous == null) ROLE.remove(); else ROLE.set(previous);
            }
        }
    }
}
