package com.anontokyo.lottery.application.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 测试可以关闭自动触发，显式控制故障窗口。生产默认开启。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "lottery.scheduling.enabled", matchIfMissing = true)
public class LotteryScheduling {
}
