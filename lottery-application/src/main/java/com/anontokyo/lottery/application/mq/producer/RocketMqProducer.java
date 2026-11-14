package com.anontokyo.lottery.application.mq.producer;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.common.Constants.ResponseCode;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.DrawOrderVO;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.service.order.IStockOrderTransaction;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Component
public class RocketMqProducer implements IStockOrderTransaction, TransactionListener {
    public static final String STOCK_TOPIC = "lottery_stock_transaction";
    public static final String FULFILLMENT_TOPIC = "lottery_award_fulfillment";
    private static final String DEADLINE = "lotteryLocalDeadline";
    private static final long LOCAL_DEADLINE_MS = TimeUnit.SECONDS.toMillis(30);
    private static final Logger log = LoggerFactory.getLogger(RocketMqProducer.class);

    private final IOrderRepository orders;
    private final TransactionMQProducer client;

    public RocketMqProducer(IOrderRepository orders,
            @Value("${lottery.rocketmq.nameserver}") String nameserver,
            @Value("${lottery.rocketmq.producer-group:lottery-order-tx}") String group,
            @Value("${lottery.rocketmq.send-timeout-ms:1500}") int sendTimeout) {
        this.orders = orders;
        this.client = new TransactionMQProducer(group);
        client.setNamesrvAddr(nameserver);
        client.setTopics(Arrays.asList(STOCK_TOPIC, FULFILLMENT_TOPIC));
        client.setSendMsgTimeout(sendTimeout);
        client.setVipChannelEnabled(false);
        client.setRetryTimesWhenSendFailed(0);
        client.setTransactionListener(this);
    }

    @PostConstruct
    public void start() throws Exception {
        client.start();
        // 进程重启后即注册事务生产者组。只启动 SDK 会等到首次发送才发现 Broker。
        try {
            client.fetchPublishMessageQueues(STOCK_TOPIC);
            client.getDefaultMQProducerImpl().getMqClientFactory().sendHeartbeatToAllBrokerWithLock();
        } catch (Exception e) {
            client.shutdown();
            throw e;
        }
    }

    @PreDestroy
    public void stop() {
        client.shutdown();
    }

    @Override
    public ResponseCode place(DrawOrderVO order) {
        AwardStockSettlementVO event = new AwardStockSettlementVO();
        BeanUtils.copyProperties(order, event);
        LocalOrder local = new LocalOrder(order);
        // SDK 同步发送 Half Message，并仅在 Broker 确认后执行本地回调。
        sendStock(event, local);
        if (local.failure != null) {
            throw local.failure;
        }
        if (local.result == null) {
            throw new IllegalStateException("Half Message 未确认，orderId=" + order.getOrderId());
        }
        return local.result;
    }

    public void sendStockSettlement(AwardStockSettlementVO event) {
        // 对账重投仍使用事务 Topic。回调只查已持久化订单，无权再次执行 TX2。
        sendStock(event, null);
    }

    private void sendStock(AwardStockSettlementVO event, LocalOrder local) {
        Message message = new Message(STOCK_TOPIC, null, event.getOrderId().toString(), encode(event));
        long deadline = System.currentTimeMillis() + (local == null ? 0 : LOCAL_DEADLINE_MS);
        message.putUserProperty(DEADLINE, Long.toString(deadline));
        try {
            requireStored(client.sendMessageInTransaction(message, local));
        } catch (Exception e) {
            throw new IllegalStateException("库存事务消息发送失败，orderId=" + event.getOrderId(), e);
        }
    }

    public void sendAwardFulfillment(AwardFulfillmentEvent event) {
        try {
            requireStored(client.send(new Message(FULFILLMENT_TOPIC, null, event.getOrderId().toString(), encode(event))));
        } catch (Exception e) {
            throw new IllegalStateException("发奖消息发送失败，orderId=" + event.getOrderId(), e);
        }
    }

    @Override
    public LocalTransactionState executeLocalTransaction(Message message, Object argument) {
        if (argument == null) {
            return checkLocalState(message, deadline(message));
        }
        LocalOrder local = (LocalOrder) argument;
        try {
            local.result = orders.saveDrawOrderWithTakeLock(local.order, deadline(message));
            return local.result == ResponseCode.SUCCESS
                    ? LocalTransactionState.COMMIT_MESSAGE : LocalTransactionState.ROLLBACK_MESSAGE;
        } catch (RuntimeException e) {
            // SDK 会接管回查。同步保留异常传回 HTTP，避免把提交结果未知返回为未中奖。
            local.failure = e;
            log.error("订单本地事务执行异常，orderId:{}", local.order.getOrderId(), e);
            return checkLocalState(message, 0);
        }
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt message) {
        LocalTransactionState state = checkLocalState(message, deadline(message));
        log.info("库存事务回查 orderId:{} state:{}", message.getKeys(), state);
        return state;
    }

    private LocalTransactionState checkLocalState(Message message, long expiresAt) {
        try {
            AwardStockSettlementVO event = JSON.parseObject(message.getBody(), AwardStockSettlementVO.class);
            switch (orders.checkStockTransaction(event.getuId(), event.getOrderId(),
                    event.getTakeId(), expiresAt)) {
                case COMMITTED:
                    return LocalTransactionState.COMMIT_MESSAGE;
                case ROLLED_BACK:
                    return LocalTransactionState.ROLLBACK_MESSAGE;
                default:
                    return LocalTransactionState.UNKNOW;
            }
        } catch (RuntimeException e) {
            // 数据库不可用时禁止猜测回滚；由 Broker 下一次回查。
            log.error("库存事务回查失败，orderId:{}", message.getKeys(), e);
            return LocalTransactionState.UNKNOW;
        }
    }

    private static long deadline(Message message) {
        return Long.parseLong(message.getUserProperty(DEADLINE));
    }

    private static byte[] encode(Object event) {
        return JSON.toJSONString(event).getBytes(StandardCharsets.UTF_8);
    }

    private static void requireStored(SendResult result) {
        if (result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("Broker 未确认持久化，状态=" + result.getSendStatus());
        }
    }

    private static final class LocalOrder {
        private final DrawOrderVO order;
        private ResponseCode result;
        private RuntimeException failure;

        private LocalOrder(DrawOrderVO order) {
            this.order = order;
        }
    }
}
