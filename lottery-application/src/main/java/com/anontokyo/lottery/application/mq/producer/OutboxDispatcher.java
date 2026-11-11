package com.anontokyo.lottery.application.mq.producer;

import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository.OutboxKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.concurrent.ListenableFuture;

import javax.annotation.PreDestroy;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 共享库存和发奖的实时投递、持久化领取、回调与补偿。 */
@Component
public class OutboxDispatcher {
    private final Logger logger = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final IOrderRepository orders;
    private final KafkaProducer producer;
    private final ThreadPoolExecutor executor;
    private final Semaphore inFlight;
    private final int leaseSeconds;
    private final int consumeTimeoutSeconds;
    private final int scanBatchSize;
    private final int scanBatches;
    private final boolean fulfillmentEnabled;
    private final LongAdder scans = new LongAdder();
    private final LongAdder candidatesRead = new LongAdder();
    private final LongAdder scanNanos = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder sendFailures = new LongAdder();

    public OutboxDispatcher(IOrderRepository orders, KafkaProducer producer,
            @Value("${lottery.outbox.workers:4}") int workers,
            @Value("${lottery.outbox.max-in-flight:128}") int maxInFlight,
            @Value("${lottery.outbox.lease-seconds:45}") int leaseSeconds,
            @Value("${lottery.outbox.consume-timeout-seconds:600}") int consumeTimeoutSeconds,
            @Value("${lottery.outbox.scan-batch-size:100}") int scanBatchSize,
            @Value("${lottery.outbox.scan-batches:3}") int scanBatches,
            @Value("${lottery.kafka.invoice.enabled:true}") boolean fulfillmentEnabled) {
        this.orders = orders;
        this.producer = producer;
        this.leaseSeconds = leaseSeconds;
        this.consumeTimeoutSeconds = consumeTimeoutSeconds;
        this.scanBatchSize = scanBatchSize;
        this.scanBatches = scanBatches;
        this.fulfillmentEnabled = fulfillmentEnabled;
        inFlight = new Semaphore(maxInFlight);
        executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxInFlight),
                new org.springframework.scheduling.concurrent.CustomizableThreadFactory("lottery-outbox-"));
    }

    @TransactionalEventListener
    public void afterOrderCommit(AwardStockSettlementVO stock) {
        // 提交回调只尝试提交任务，连接仍由原事务归还，实际发送在独立线程。
        offer(OutboxKind.STOCK, stock.getOrderId(), 0, true, null,
                () -> producer.sendStockSettlement(stock));
    }

    public void afterStockSettled(AwardFulfillmentEvent award) {
        if (fulfillmentEnabled) {
            offer(OutboxKind.FULFILLMENT, award.getOrderId(), 0, true, null,
                    () -> producer.sendAwardFulfillment(award));
        }
    }

    public void recover(OutboxKind kind) {
        if (kind == OutboxKind.FULFILLMENT && !fulfillmentEnabled) {
            return;
        }
        for (int batch = 0; batch < scanBatches; batch++) {
            long start = System.nanoTime();
            List<DrawOrderVO> candidates = orders.scanOutbox(kind, scanBatchSize);
            scans.increment();
            candidatesRead.add(candidates.size());
            scanNanos.add(System.nanoTime() - start);
            if (candidates.isEmpty()) {
                return;
            }
            logger.info("outbox recovery kind:{} candidates:{} queryMs:{}",
                    kind, candidates.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            CountDownLatch completed = new CountDownLatch(candidates.size());
            for (DrawOrderVO candidate : candidates) {
                if (!offerCandidate(kind, candidate, completed)) {
                    // 实时与补偿共用总配额。饱和时本轮停止，剩余候选仍由数据库保存。
                    return;
                }
            }
            if (candidates.size() < scanBatchSize || !awaitBatch(completed)) {
                return;
            }
        }
    }

    private boolean awaitBatch(CountDownLatch completed) {
        try {
            // 只在对应调度线程等待有限时间，避免重复扫描仍在发送的同一批候选。
            return completed.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean offerCandidate(OutboxKind kind, DrawOrderVO order, CountDownLatch completed) {
        if (kind == OutboxKind.STOCK) {
            AwardStockSettlementVO stock = new AwardStockSettlementVO();
            BeanUtils.copyProperties(order, stock);
            return offer(kind, order.getOrderId(), order.getStockAttempt(), false, completed,
                    () -> producer.sendStockSettlement(stock));
        }
        AwardFulfillmentEvent award = new AwardFulfillmentEvent();
        BeanUtils.copyProperties(order, award);
        return offer(kind, order.getOrderId(), order.getFulfillmentAttempt(), false, completed,
                () -> producer.sendAwardFulfillment(award));
    }

    private boolean offer(OutboxKind kind, long orderId, int attempt, boolean immediate, CountDownLatch batch,
            Supplier<ListenableFuture<SendResult<String, Object>>> send) {
        // 许可覆盖排队、Kafka 在途和结果落库，避免 Broker 停机时在途对象无界增长。
        if (!inFlight.tryAcquire()) {
            rejected.increment();
            logger.debug("outbox capacity full, kind:{} orderId:{}", kind, orderId);
            return false;
        }
        try {
            executor.execute(() -> dispatch(kind, orderId, attempt, immediate, batch, send));
            return true;
        } catch (RejectedExecutionException e) {
            release(batch);
            logger.warn("outbox task rejected kind:{} orderId:{}", kind, orderId, e);
            return false;
        }
    }

    private void dispatch(OutboxKind kind, long orderId, int attempt, boolean immediate, CountDownLatch batch,
            Supplier<ListenableFuture<SendResult<String, Object>>> send) {
        try {
            if (!orders.claimOutbox(kind, orderId, attempt, immediate, leaseSeconds)) {
                release(batch);
                return;
            }
            if (attempt > 0) {
                retries.increment();
            }
        } catch (RuntimeException e) {
            logger.error("outbox claim failed kind:{} orderId:{}", kind, orderId, e);
            release(batch);
            return;
        }

        try {
            // Kafka 回调线程只交接结果，数据库状态写入继续由有界工作线程处理。
            send.get().addCallback(result -> enqueueResult(kind, orderId, attempt + 1, batch, null),
                    error -> enqueueResult(kind, orderId, attempt + 1, batch, error));
        } catch (RuntimeException e) {
            finish(kind, orderId, attempt + 1, batch, e);
        }
    }

    private void enqueueResult(OutboxKind kind, long orderId, int attempt, CountDownLatch batch, Throwable error) {
        try {
            executor.execute(() -> finish(kind, orderId, attempt, batch, error));
        } catch (RejectedExecutionException rejected) {
            // 结果来不及写入时，领取租期本身就是持久恢复点。
            release(batch);
            logger.warn("outbox result rejected kind:{} orderId:{} attempt:{}", kind, orderId, attempt, rejected);
        }
    }

    private void finish(OutboxKind kind, long orderId, int attempt, CountDownLatch batch, Throwable error) {
        try {
            boolean sent = error == null;
            int delay = sent ? consumeTimeoutSeconds : Math.min(300, 5 << Math.min(6, attempt - 1));
            orders.finishOutbox(kind, orderId, attempt, sent, delay);
            if (!sent) {
                sendFailures.increment();
                logger.warn("outbox send failed kind:{} orderId:{} attempt:{} retrySeconds:{}",
                        kind, orderId, attempt, delay, error);
            }
        } catch (RuntimeException e) {
            logger.error("outbox result persistence failed kind:{} orderId:{} attempt:{}", kind, orderId, attempt, e);
        } finally {
            release(batch);
        }
    }

    private void release(CountDownLatch batch) {
        inFlight.release();
        if (batch != null) {
            batch.countDown();
        }
    }

    public Map<String, Long> metrics() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("scans", scans.sum());
        counts.put("candidates", candidatesRead.sum());
        counts.put("scanNanos", scanNanos.sum());
        counts.put("retries", retries.sum());
        counts.put("rejected", rejected.sum());
        counts.put("sendFailures", sendFailures.sum());
        return counts;
    }

    @PreDestroy
    public void close() {
        executor.shutdown();
    }
}
