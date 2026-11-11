package com.anontokyo.lottery.application.mq.producer;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.AwardFulfillmentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;

import javax.annotation.Resource;

@Component
public class KafkaProducer {

    public static final String TOPIC_AWARD_FULFILLMENT = "lottery_invoice";
    private static final String TOPIC_STOCK_SETTLEMENT = "lottery_strategy_award_stock";

    private final Logger logger = LoggerFactory.getLogger(KafkaProducer.class);

    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;

    public ListenableFuture<SendResult<String, Object>> sendAwardFulfillment(AwardFulfillmentEvent event) {
        String objJson = JSON.toJSONString(event);
        if (logger.isDebugEnabled()) {
            logger.debug("send mq topic:{} bizId:{} message:{}", TOPIC_AWARD_FULFILLMENT, event.getuId(), objJson);
        }
        return kafkaTemplate.send(TOPIC_AWARD_FULFILLMENT, String.valueOf(event.getOrderId()), objJson);
    }

    public ListenableFuture<SendResult<String, Object>> sendStockSettlement(
            AwardStockSettlementVO awardStockSettlementVO) {
        String objJson = JSON.toJSONString(awardStockSettlementVO);
        if (logger.isDebugEnabled()) {
            logger.debug("send stock settlement event topic:{} bizId:{} message:{}", TOPIC_STOCK_SETTLEMENT,
                    awardStockSettlementVO.getOrderId(), objJson);
        }
        return kafkaTemplate.send(TOPIC_STOCK_SETTLEMENT, String.valueOf(awardStockSettlementVO.getOrderId()), objJson);
    }
}
