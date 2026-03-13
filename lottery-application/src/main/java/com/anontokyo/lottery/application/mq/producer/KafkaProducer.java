package com.anontokyo.lottery.application.mq.producer;


import com.anontokyo.lottery.domain.activity.model.vo.ActivityPartakeRecordVO;
import com.anontokyo.lottery.domain.activity.model.vo.InvoiceVO;
import com.anontokyo.lottery.domain.strategy.model.vo.StrategyAwardStockVO;
import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;

import javax.annotation.Resource;


/**
 * @description:
 * @author：anontokyo
 * @date: 2026/1/29
 */
@Component
public class KafkaProducer {

    private Logger logger = LoggerFactory.getLogger(KafkaProducer.class);

    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 中奖发货单
     */
    public static final String TOPIC_INVOICE = "lottery_invoice";

    /**
     * MQ主题：活动领取记录
     *
     */
    private static final String TOPIC_ACTIVITY_PARTAKE = "lottery_activity_partake";

    /**
     * 发送中奖物品发货单消息
     * @param invoice
     * @return
     */
    public ListenableFuture<SendResult<String, Object>> sendLotteryInvoice(InvoiceVO invoice) {
        String objJson = JSON.toJSONString(invoice);
        logger.info("发送MQ消息 topic：{} bizId：{} message：{}", TOPIC_INVOICE, invoice.getuId(), objJson);
        return kafkaTemplate.send(TOPIC_INVOICE, objJson);
    }


    public ListenableFuture<SendResult<String, Object>> sendLotteryActivityPartakeRecord(ActivityPartakeRecordVO activityPartakeRecord) {
        String objJson = JSON.toJSONString(activityPartakeRecord);
        logger.info("发送MQ消息(领取活动记录) topic：{} bizId：{} message：{}", TOPIC_ACTIVITY_PARTAKE, activityPartakeRecord.getuId(), objJson);
        return kafkaTemplate.send(TOPIC_ACTIVITY_PARTAKE, objJson);
    }

    /**
     * MQ主题：策略奖品库存扣减（Redis扣减后异步落库）
     */
    private static final String TOPIC_STRATEGY_AWARD_STOCK = "lottery_strategy_award_stock";

    /**
     * 发送策略奖品库存扣减消息
     * @param strategyAwardStockVO 策略奖品库存信息
     * @return
     */
    public ListenableFuture<SendResult<String, Object>> sendStrategyAwardStock(StrategyAwardStockVO strategyAwardStockVO) {
        String objJson = JSON.toJSONString(strategyAwardStockVO);
        logger.info("发送MQ消息(策略奖品库存扣减) topic：{} message：{}", TOPIC_STRATEGY_AWARD_STOCK, objJson);
        return kafkaTemplate.send(TOPIC_STRATEGY_AWARD_STOCK, objJson);
    }
}
