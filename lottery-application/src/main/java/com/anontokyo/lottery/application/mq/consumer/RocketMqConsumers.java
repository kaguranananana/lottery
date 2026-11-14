package com.anontokyo.lottery.application.mq.consumer;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.application.mq.producer.RocketMqProducer;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.function.Consumer;

@Component
public class RocketMqConsumers {
    private static final Logger log = LoggerFactory.getLogger(RocketMqConsumers.class);

    private final String nameserver;
    private final String stockGroup;
    private final String awardGroup;
    private final int threads;
    private final boolean autoStart;
    private final StockSettlementListener stock;
    private final AwardFulfillmentListener award;
    private DefaultMQPushConsumer stockClient;
    private DefaultMQPushConsumer awardClient;

    public RocketMqConsumers(StockSettlementListener stock, AwardFulfillmentListener award,
            @Value("${lottery.rocketmq.nameserver}") String nameserver,
            @Value("${lottery.rocketmq.stock-group:lottery-stock}") String stockGroup,
            @Value("${lottery.rocketmq.fulfillment-group:lottery-fulfillment}") String awardGroup,
            @Value("${lottery.rocketmq.consumer-threads:4}") int threads,
            @Value("${lottery.rocketmq.consumers-auto-start:true}") boolean autoStart) {
        this.nameserver = nameserver;
        this.stockGroup = stockGroup;
        this.awardGroup = awardGroup;
        this.threads = threads;
        this.autoStart = autoStart;
        this.stock = stock;
        this.award = award;
    }

    @PostConstruct
    public void initialize() throws Exception {
        if (autoStart) {
            start();
        }
    }

    public synchronized void start() throws Exception {
        if (stockClient != null) {
            return;
        }
        stockClient = startConsumer(stockGroup, RocketMqProducer.STOCK_TOPIC,
                AwardStockSettlementVO.class, stock::onMessage);
        try {
            awardClient = startConsumer(awardGroup, RocketMqProducer.FULFILLMENT_TOPIC,
                    AwardFulfillmentEvent.class, award::onMessage);
        } catch (Exception e) {
            stop();
            throw e;
        }
    }

    private <T> DefaultMQPushConsumer startConsumer(String group, String topic, Class<T> type, Consumer<T> handler)
            throws Exception {
        DefaultMQPushConsumer client = new DefaultMQPushConsumer(group);
        client.setNamesrvAddr(nameserver);
        client.setVipChannelEnabled(false);
        client.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        client.setConsumeThreadMin(threads);
        client.setConsumeThreadMax(threads);
        client.subscribe(topic, "*");
        client.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            // 默认每次最多一条。数据库持久化失败时由 Broker 重试，耗尽后仍有订单对账。
            for (MessageExt message : messages) {
                try {
                    handler.accept(JSON.parseObject(message.getBody(), type));
                } catch (RuntimeException e) {
                    log.error("消息消费失败 topic:{} orderId:{} msgId:{}", topic, message.getKeys(), message.getMsgId(), e);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        client.start();
        return client;
    }

    @PreDestroy
    public synchronized void stop() {
        if (awardClient != null) {
            awardClient.shutdown();
            awardClient = null;
        }
        if (stockClient != null) {
            stockClient.shutdown();
            stockClient = null;
        }
    }
}
