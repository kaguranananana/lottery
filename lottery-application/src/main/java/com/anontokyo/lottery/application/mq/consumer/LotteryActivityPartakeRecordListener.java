package com.anontokyo.lottery.application.mq.consumer;

import com.alibaba.fastjson.JSON;
import com.anontokyo.lottery.domain.activity.model.vo.ActivityPartakeRecordVO;
import com.anontokyo.lottery.domain.activity.service.partake.IActivityPartake;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Optional;

/**
 * @description: Activity partake record consumer
 * @author: anontokyo
 * @date: 2026/1/30
 */
@Component
public class LotteryActivityPartakeRecordListener {

    private final Logger logger = LoggerFactory.getLogger(LotteryActivityPartakeRecordListener.class);

    @Resource
    private IActivityPartake activityPartake;

    @KafkaListener(topics = "lottery_activity_partake", groupId = "lottery")
    public void onMessage(ConsumerRecord<?, ?> record, Acknowledgment ack,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        Optional<?> message = Optional.ofNullable(record.value());
        if (!message.isPresent()) {
            return;
        }

        try {
            ActivityPartakeRecordVO activityPartakeRecordVO = JSON.parseObject((String) message.get(),
                    ActivityPartakeRecordVO.class);
            logger.info("Consume mq message, async update activity stock, message:{}", message.get());
            activityPartake.updateActivityStock(activityPartakeRecordVO);

            // manual_immediate mode requires explicit ack to avoid duplicate re-consume.
            ack.acknowledge();
        } catch (Exception e) {
            logger.error("Consume mq message failed, topic:{} message:{}", topic, message.get(), e);
            throw e;
        }
    }

}
