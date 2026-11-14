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
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/** 实时投递发奖，并对账超过完成时限的订单。 */
@Component
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final IOrderRepository orders;
    private final RocketMqProducer producer;
    private final ThreadPoolExecutor executor;
    private final int scanBatchSize;
    private final LongAdder rejected = new LongAdder();
    private final LongAdder sendFailures = new LongAdder();

    public OutboxDispatcher(IOrderRepository orders, RocketMqProducer producer,
            @Value("${lottery.outbox.workers:6}") int workers,
            @Value("${lottery.outbox.max-in-flight:128}") int maxQueued,
            @Value("${lottery.outbox.scan-batch-size:32}") int scanBatchSize) {
        this.orders = orders;
        this.producer = producer;
        this.scanBatchSize = scanBatchSize;
        this.executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxQueued), new CustomizableThreadFactory("lottery-outbox-"));
    }

    public void afterStockSettled(AwardFulfillmentEvent award) {
        offer(award.getOrderId(), () -> producer.sendAwardFulfillment(award));
    }

    public void recover(OutboxKind kind) {
        for (DrawOrderVO order : orders.scanOutbox(kind, scanBatchSize)) {
            if (!offer(order.getOrderId(), () -> recoverOrder(kind, order))) {
                return;
            }
        }
    }

    private void recoverOrder(OutboxKind kind, DrawOrderVO order) {
        // 只延后核对时间，发送结果不再写 SENT/FAIL 或回调代数。
        int leaseSeconds = kind == OutboxKind.STOCK ? 600 : 45;
        if (!orders.claimOutbox(kind, order.getOrderId(), leaseSeconds)) {
            return;
        }
        if (kind == OutboxKind.STOCK) {
            AwardStockSettlementVO stock = new AwardStockSettlementVO();
            BeanUtils.copyProperties(order, stock);
            producer.sendStockSettlement(stock);
        } else {
            AwardFulfillmentEvent award = new AwardFulfillmentEvent();
            BeanUtils.copyProperties(order, award);
            producer.sendAwardFulfillment(award);
        }
    }

    private boolean offer(long orderId, Runnable send) {
        try {
            executor.execute(() -> {
                try {
                    send.run();
                } catch (RuntimeException e) {
                    sendFailures.increment();
                    // 未完成义务仍保存在订单中，发送失败只记录日志。
                    log.warn("订单义务投递失败 orderId:{}", orderId, e);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            rejected.increment();
            log.warn("订单义务投递队列已满 orderId:{}", orderId);
            return false;
        }
    }

    public Map<String, Long> metrics() {
        Map<String, Long> metrics = new LinkedHashMap<>();
        metrics.put("rejected", rejected.sum());
        metrics.put("sendFailures", sendFailures.sum());
        return metrics;
    }

    @PreDestroy
    public void close() {
        executor.shutdown();
    }
}
