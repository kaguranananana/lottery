package com.anontokyo.lottery.application.mq.consumer;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.application.mq.producer.KafkaProducer;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.award.service.order.IAwardOrder;
import com.anontokyo.lottery.domain.strategy.repository.IStrategyRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

import javax.annotation.Resource;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class LotteryStrategyAwardStockListener {

    private final Logger logger = LoggerFactory.getLogger(LotteryStrategyAwardStockListener.class);

    @Resource
    private IStrategyRepository strategyRepository;

    @Resource
    private IAwardOrder awardOrder;

    @Resource
    private KafkaProducer kafkaProducer;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${lottery.cache.strategy-award-stock-mq-dedup-redis-ttl-seconds:86400}")
    private long strategyAwardStockMqDedupRedisTtlSeconds;

    @Value("${lottery.cache.strategy-award-stock-mq-processing-redis-ttl-seconds:300}")
    private long strategyAwardStockMqProcessingRedisTtlSeconds;

    @Value("${lottery.kafka.invoice.enabled:true}")
    private boolean invoiceMqEnabled;

    @KafkaListener(topics = "lottery_strategy_award_stock",
            groupId = "${lottery.kafka.consumer.strategy-award-stock-group-id:lottery-strategy-award-stock}",
            concurrency = "${lottery.kafka.consumer.strategy-award-stock-concurrency:8}")
    public void onMessage(ConsumerRecord<?, ?> record, Acknowledgment ack,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        Optional<?> message = Optional.ofNullable(record.value());
        if (!message.isPresent()) {
            ack.acknowledge();
            return;
        }

        AwardStockSettlementVO settlementVO = JSON.parseObject((String) message.get(), AwardStockSettlementVO.class);
        boolean claimed = false;
        try {
            if (isProcessed(settlementVO.getOrderId())
                    || awardOrder.isStockMqCompleted(settlementVO.getuId(), settlementVO.getOrderId())) {
                ack.acknowledge();
                return;
            }

            claimed = claimProcessing(settlementVO.getOrderId());
            if (!claimed) {
                ack.acknowledge();
                return;
            }

            if (logger.isDebugEnabled()) {
                logger.debug("consume strategy award stock topic:{} message:{}", topic, message.get());
            }

            InvoiceVO invoiceVO = awardOrder.queryInvoiceByOrderId(settlementVO.getuId(), settlementVO.getOrderId());
            if (invoiceVO == null) {
                markProcessed(settlementVO.getOrderId());
                clearDispatchMarker(settlementVO.getOrderId());
                logger.warn("skip stale strategy award stock message, order not found uId:{} orderId:{}",
                        settlementVO.getuId(), settlementVO.getOrderId());
                ack.acknowledge();
                return;
            }

            boolean deductSuccess = strategyRepository.deductStock(
                    settlementVO.getStrategyId(), settlementVO.getAwardId());
            if (!deductSuccess) {
                awardOrder.updateStockMqState(settlementVO.getuId(), settlementVO.getOrderId(),
                        Constants.MQState.FAIL.getCode());
                logger.error("deduct strategy award stock failed uId:{} orderId:{} strategyId:{} awardId:{}",
                        settlementVO.getuId(), settlementVO.getOrderId(), settlementVO.getStrategyId(),
                        settlementVO.getAwardId());
                ack.acknowledge();
                return;
            }

            awardOrder.updateStockMqState(settlementVO.getuId(), settlementVO.getOrderId(),
                    Constants.MQState.COMPLETE.getCode());
            markProcessed(settlementVO.getOrderId());
            clearDispatchMarker(settlementVO.getOrderId());
            sendInvoiceMessage(invoiceVO);
            ack.acknowledge();
        } catch (Exception e) {
            logger.error("consume strategy award stock failed topic:{} message:{}", topic, message.get(), e);
            throw e;
        } finally {
            if (claimed) {
                releaseProcessing(settlementVO.getOrderId());
            }
        }
    }

    private void sendInvoiceMessage(InvoiceVO invoiceVO) {
        String uId = invoiceVO.getuId();
        Long orderId = invoiceVO.getOrderId();
        if (!invoiceMqEnabled) {
            awardOrder.updateInvoiceMqState(uId, orderId, Constants.MQState.FAIL.getCode());
            if (logger.isDebugEnabled()) {
                logger.debug("invoice mq disabled after stock settlement uId:{} orderId:{}", uId, orderId);
            }
            return;
        }

        try {
            ListenableFuture<SendResult<String, Object>> future = kafkaProducer.sendLotteryInvoice(invoiceVO);
            future.addCallback(new ListenableFutureCallback<SendResult<String, Object>>() {

                @Override
                public void onSuccess(SendResult<String, Object> result) {
                    awardOrder.updateInvoiceMqState(invoiceVO.getuId(), invoiceVO.getOrderId(),
                            Constants.MQState.COMPLETE.getCode());
                }

                @Override
                public void onFailure(Throwable throwable) {
                    awardOrder.updateInvoiceMqState(invoiceVO.getuId(), invoiceVO.getOrderId(),
                            Constants.MQState.FAIL.getCode());
                    logger.error("invoice mq send failed after stock settlement uId:{} orderId:{}",
                            invoiceVO.getuId(), invoiceVO.getOrderId(), throwable);
                }
            });
        } catch (Exception e) {
            awardOrder.updateInvoiceMqState(invoiceVO.getuId(), invoiceVO.getOrderId(),
                    Constants.MQState.FAIL.getCode());
            logger.error("invoice mq send exception after stock settlement uId:{} orderId:{}", invoiceVO.getuId(),
                    invoiceVO.getOrderId(), e);
        }
    }

    private boolean claimProcessing(Long orderId) {
        if (orderId == null) {
            return true;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_PROCESSING(orderId);
        try {
            Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",
                    strategyAwardStockMqProcessingRedisTtlSeconds, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(success);
        } catch (Exception e) {
            logger.warn("claim strategy award stock mq processing key failed, orderId:{}", orderId, e);
            return false;
        }
    }

    private void releaseProcessing(Long orderId) {
        if (orderId == null) {
            return;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_PROCESSING(orderId);
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            logger.warn("release strategy award stock mq processing key failed, orderId:{}", orderId, e);
        }
    }

    private void clearDispatchMarker(Long orderId) {
        if (orderId == null) {
            return;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_DISPATCHING(orderId);
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            logger.warn("clear strategy award stock mq dispatch marker failed, orderId:{}", orderId, e);
        }
    }

    private boolean isProcessed(Long orderId) {
        if (orderId == null) {
            return false;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_DONE(orderId);
        try {
            String value = stringRedisTemplate.opsForValue().get(key);
            return value != null && value.length() > 0;
        } catch (Exception e) {
            logger.warn("read strategy award stock mq dedup key failed, orderId:{}", orderId, e);
            return false;
        }
    }

    private void markProcessed(Long orderId) {
        if (orderId == null) {
            return;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_STRATEGY_AWARD_STOCK_MQ_DONE(orderId);
        try {
            stringRedisTemplate.opsForValue().set(key, "1", strategyAwardStockMqDedupRedisTtlSeconds,
                    TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("set strategy award stock mq dedup key failed, orderId:{}", orderId, e);
        }
    }
}
