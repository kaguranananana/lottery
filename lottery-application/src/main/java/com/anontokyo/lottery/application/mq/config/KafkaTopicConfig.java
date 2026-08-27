package com.anontokyo.lottery.application.mq.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic lotteryInvoiceTopic(
            @Value("${lottery.kafka.topic.invoice.partitions:8}") int partitions,
            @Value("${lottery.kafka.topic.invoice.replicas:1}") short replicas) {
        return new NewTopic("lottery_invoice", partitions, replicas);
    }

    @Bean
    public NewTopic lotteryStrategyAwardStockTopic(
            @Value("${lottery.kafka.topic.strategy-award-stock.partitions:8}") int partitions,
            @Value("${lottery.kafka.topic.strategy-award-stock.replicas:1}") short replicas) {
        return new NewTopic("lottery_strategy_award_stock", partitions, replicas);
    }
}
