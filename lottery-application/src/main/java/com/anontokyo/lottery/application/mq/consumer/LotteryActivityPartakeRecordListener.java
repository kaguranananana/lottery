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

@Component
public class LotteryActivityPartakeRecordListener {

    private final Logger logger = LoggerFactory.getLogger(LotteryActivityPartakeRecordListener.class);

    @Resource
    private IActivityPartake activityPartake;

    @KafkaListener(topics = "lottery_activity_partake",
            groupId = "${lottery.kafka.consumer.activity-partake-group-id:lottery-activity-partake}",
            concurrency = "${lottery.kafka.consumer.activity-partake-concurrency:4}")
    public void onMessage(ConsumerRecord<?, ?> record, Acknowledgment ack,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        Optional<?> message = Optional.ofNullable(record.value());
        if (!message.isPresent()) {
            ack.acknowledge();
            return;
        }

        try {
            ActivityPartakeRecordVO activityPartakeRecordVO = JSON.parseObject((String) message.get(),
                    ActivityPartakeRecordVO.class);
            if (logger.isDebugEnabled()) {
                logger.debug("consume mq activity partake topic:{} message:{}", topic, message.get());
            }
            activityPartake.updateActivityStock(activityPartakeRecordVO);
            ack.acknowledge();
        } catch (Exception e) {
            logger.error("consume mq activity partake failed, topic:{} message:{}", topic, message.get(), e);
            throw e;
        }
    }
}
