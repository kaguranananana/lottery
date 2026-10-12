package com.anontokyo.lottery.application.mq.consumer;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.common.Constants;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.award.repository.IOrderRepository;
import com.anontokyo.lottery.domain.award.service.fulfillment.AwardFulfillmentHandler;
import com.anontokyo.lottery.domain.support.lock.IInvoiceMqProcessGuard;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 发奖消费者。Spring 注入全部 {@link AwardFulfillmentHandler} 后按奖品类型构建注册表，
 * 不再需要 Config+Factory 的双层包装。
 */
@Component
public class LotteryInvoiceListener {

    private final Logger logger = LoggerFactory.getLogger(LotteryInvoiceListener.class);

    @Resource
    private List<AwardFulfillmentHandler> fulfillmentHandlers;

    @Resource
    private IOrderRepository orderRepository;

    @Resource
    private IInvoiceMqProcessGuard invoiceMqProcessGuard;

    private final Map<Integer, AwardFulfillmentHandler> handlerRegistry = new HashMap<>();

    @PostConstruct
    public void initRegistry() {
        for (AwardFulfillmentHandler handler : fulfillmentHandlers) {
            AwardFulfillmentHandler previous = handlerRegistry.put(handler.supportType(), handler);
            if (previous != null) {
                throw new IllegalStateException("duplicate award fulfillment handler for type "
                        + handler.supportType());
            }
        }
        logger.info("award fulfillment handlers registered: {}", handlerRegistry.keySet());
    }

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

        try {
            InvoiceVO invoiceVO = JSON.parseObject((String) message.get(), InvoiceVO.class);

            // DB 兜底：已发奖完成的订单直接跳过（重复投递安全）
            if (orderRepository.isAwardCompleted(invoiceVO.getuId(), invoiceVO.getOrderId())) {
                // 重放时修复发奖已完成但消息尚未收口的窗口，更新失败则交还 Kafka 重投。
                orderRepository.updateInvoiceMqState(invoiceVO.getuId(), invoiceVO.getOrderId(),
                        Constants.MQState.COMPLETE.getCode());
                // 只有数据库收口调用成功返回后才提交消费位点。
                ack.acknowledge();
                return;
            }

            if (invoiceMqProcessGuard.tryClaimProcessing(invoiceVO.getOrderId())) {
                // grant_state CAS 同时是本地业务幂等兜底，orderId 即未来真实渠道的 idempotencyKey
                AwardFulfillmentHandler handler = handlerRegistry.get(invoiceVO.getAwardType());
                if (handler == null) {
                    throw new IllegalStateException("no award fulfillment handler for type "
                            + invoiceVO.getAwardType() + ", orderId:" + invoiceVO.getOrderId());
                }
                Constants.AwardState state = handler.fulfill(invoiceVO).getState();
                if (!Constants.AwardState.SUCCESS.getCode().equals(state.getCode())) {
                    throw new IllegalStateException("award fulfillment failed, orderId:" + invoiceVO.getOrderId()
                            + " info:" + state.getInfo());
                }
                invoiceMqProcessGuard.releaseProcessing(invoiceVO.getOrderId());
                logger.info("award fulfilled uId:{} orderId:{} awardId:{}",
                        invoiceVO.getuId(), invoiceVO.getOrderId(), invoiceVO.getAwardId());
            } else {
                // 其他实例正在处理同一订单：等待其完成后再投递校验即可
                if (!orderRepository.isAwardCompleted(invoiceVO.getuId(), invoiceVO.getOrderId())) {
                    throw new IllegalStateException("invoice mq message is processing, orderId:"
                            + invoiceVO.getOrderId());
                }
            }
            // 本消费者发奖成功，或已确认并发消费者完成后，统一收口消息状态。
            orderRepository.updateInvoiceMqState(invoiceVO.getuId(), invoiceVO.getOrderId(),
                    Constants.MQState.COMPLETE.getCode());
            // 先持久化消费结果，再提交位点，重放可以修复中途失败。
            ack.acknowledge();
        } catch (Exception e) {
            logger.error("consume mq invoice failed, topic:{} message:{}", topic, message.get(), e);
            throw e;
        }
    }
}
