# 日志规范

## 关联业务链路

沿用 SLF4J 参数化日志与现有 LoggerFactory。[LotteryController.java](../../../lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java) 记录 requestId、userId、activityId；[ActivityProcessImpl.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java) 记录 takeId 复用、订单回放与慢调用；[KafkaProducer.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/mq/producer/KafkaProducer.java)、[LotteryStrategyAwardStockListener.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryStrategyAwardStockListener.java)、[LotteryInvoiceListener.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryInvoiceListener.java) 用 orderId 关联消息和订单。

新增抽奖诊断带上相关 requestId、uId、activityId，订单和异步事件带 orderId，库存事件增加 strategyId、awardId 或库存 key。字段足以串联实际处理步骤即可，不引入与定位无关的追踪框架。

## 日志级别与成本

- info：活动状态变更、幂等回放、结算已处理、发奖完成和补偿批次。
- warn：业务冲突、非法状态转换、命中售罄奖品、锁过期或非属主释放。
- error：库存状态不可用、需要对账的库存分歧、消息投递/消费异常；保留 Throwable。
- debug：完整消息或成功细节，仅在必要时输出；序列化等额外成本沿用 isDebugEnabled() 检查。

[ActivityProcessImpl.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java) 用 System.nanoTime 统计阶段耗时，超过 3000ms 输出 total、partake、draw、settle 与业务码。新增性能日志先定位实际慢步骤，不宣称只有日志存在就已完成压测。

## 当前可观测边界

现有日志是业务标识与堆栈，没有统一 MDC 或分布式 Trace。MyBatis 在 [application.yml](../../../lottery-interfaces/src/main/resources/application.yml) 使用 NoLoggingImpl，不能依赖默认 SQL 日志定位路由；涉及分片问题应在任务内明确库、表、DAO 与相关订单。历史代码有完整 payload、awardContent 日志，新增日志只输出定位所需字段，避免复制过多业务正文。

捕获后记录日志并不等于错误已正确传播；异常策略仍须遵循[错误处理](./error-handling.md)。
