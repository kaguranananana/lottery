package com.anontokyo.lottery.application.mq.producer;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.domain.award.model.vo.AwardStockSettlementVO;
import com.anontokyo.lottery.domain.award.model.vo.InvoiceVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;

import javax.annotation.Resource;

@Component
public class KafkaProducer {

    public static final String TOPIC_INVOICE = "lottery_invoice";
    private static final String TOPIC_STRATEGY_AWARD_STOCK = "lottery_strategy_award_stock";

    private final Logger logger = LoggerFactory.getLogger(KafkaProducer.class);

    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;

    public ListenableFuture<SendResult<String, Object>> sendLotteryInvoice(InvoiceVO invoice) {
        String objJson = JSON.toJSONString(invoice);
        if (logger.isDebugEnabled()) {
            logger.debug("send mq topic:{} bizId:{} message:{}", TOPIC_INVOICE, invoice.getuId(), objJson);
        }
        return kafkaTemplate.send(TOPIC_INVOICE, String.valueOf(invoice.getOrderId()), objJson);
    }

    public ListenableFuture<SendResult<String, Object>> sendStrategyAwardStock(
            AwardStockSettlementVO awardStockSettlementVO) {
        String objJson = JSON.toJSONString(awardStockSettlementVO);
        if (logger.isDebugEnabled()) {
            logger.debug("send mq strategy award stock topic:{} bizId:{} message:{}", TOPIC_STRATEGY_AWARD_STOCK,
                    awardStockSettlementVO.getOrderId(), objJson);
        }
        return kafkaTemplate.send(TOPIC_STRATEGY_AWARD_STOCK, String.valueOf(awardStockSettlementVO.getOrderId()), objJson);
    }
}
