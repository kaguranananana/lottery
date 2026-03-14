package com.anontokyo.lottery.application.mq.consumer;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.req.GoodsReq;
import com.anontokyo.lottery.domain.award.model.res.DistributionRes;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.service.factory.DistributionGoodsFactory;
import com.anontokyo.lottery.domain.award.service.goods.IDistributionGoods;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class LotteryInvoiceListener {

    private final Logger logger = LoggerFactory.getLogger(LotteryInvoiceListener.class);

    @Resource
    private DistributionGoodsFactory distributionGoodsFactory;

    @Resource
    private IOrderRepository orderRepository;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${lottery.cache.invoice-mq-processing-redis-ttl-seconds:300}")
    private long invoiceMqProcessingRedisTtlSeconds;

    @KafkaListener(topics = "lottery_invoice",
            groupId = "${lottery.kafka.consumer.invoice-group-id:lottery-invoice}",
            concurrency = "${lottery.kafka.consumer.invoice-concurrency:8}")
    public void onMessage(ConsumerRecord<?, ?> record, Acknowledgment ack,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        Optional<?> message = Optional.ofNullable(record.value());
        if (!message.isPresent()) {
            ack.acknowledge();
            return;
        }

        boolean claimed = false;
        try {
            InvoiceVO invoiceVO = JSON.parseObject((String) message.get(), InvoiceVO.class);
            if (orderRepository.isAwardCompleted(invoiceVO.getuId(), invoiceVO.getOrderId())) {
                ack.acknowledge();
                return;
            }

            claimed = claimProcessing(invoiceVO.getOrderId());
            if (!claimed) {
                if (orderRepository.isAwardCompleted(invoiceVO.getuId(), invoiceVO.getOrderId())) {
                    ack.acknowledge();
                    return;
                }
                throw new IllegalStateException("invoice mq message is processing");
            }

            IDistributionGoods distributionGoodsService = distributionGoodsFactory
                    .getDistributionGoodsService(invoiceVO.getAwardType());
            DistributionRes distributionRes = distributionGoodsService
                    .doDistribution(new GoodsReq(invoiceVO.getuId(), invoiceVO.getOrderId(), invoiceVO.getAwardId()));

            Assert.isTrue(Constants.AwardState.SUCCESS.getCode().equals(distributionRes.getCode()),
                    distributionRes.getInfo());

            if (logger.isDebugEnabled()) {
                logger.debug("consume mq invoice topic:{} bizId:{} distributionRes:{}", topic, invoiceVO.getuId(),
                        JSON.toJSONString(distributionRes));
            }

            if (claimed) {
                releaseProcessing(invoiceVO.getOrderId());
            }
            ack.acknowledge();
        } catch (Exception e) {
            if (claimed) {
                try {
                    InvoiceVO invoiceVO = JSON.parseObject((String) message.get(), InvoiceVO.class);
                    releaseProcessing(invoiceVO.getOrderId());
                } catch (Exception releaseException) {
                    logger.warn("release invoice processing key failed, message:{}", message.get(), releaseException);
                }
            }
            logger.error("consume mq invoice failed, topic:{} message:{}", topic, message.get(), e);
            throw e;
        }
    }

    private boolean claimProcessing(Long orderId) {
        if (orderId == null) {
            return false;
        }
        String key = Constants.RedisKey.KEY_LOTTERY_INVOICE_MQ_PROCESSING(orderId);
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",
                invoiceMqProcessingRedisTtlSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    private void releaseProcessing(Long orderId) {
        if (orderId == null) {
            return;
        }
        stringRedisTemplate.delete(Constants.RedisKey.KEY_LOTTERY_INVOICE_MQ_PROCESSING(orderId));
    }
}
