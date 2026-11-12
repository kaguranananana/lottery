# 应用编排、消息与任务

## 同步抽奖

[ActivityProcessImpl.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java) 的 doDrawProcess 是编排入口。先获得活动与用户维度的短锁，再按 requestId 查 take：已消费回放原订单或未中奖结果；未消费继续原 take；不存在才调用参与服务。锁在 finally 中携带原 token 释放。

未中奖需要消费 take；中奖创建订单。订单服务返回非成功业务码时，当前编排回补已预扣奖品库存。SQL 异常的回补由仓储按事务完成状态处理；不能在编排外层无条件捕获并回补。奖品库存 Key 缺失抛出，保留未消费 take，不得返回未中奖。同步方法完成后返回结果，库存结算通过订单 Outbox 异步驱动，不在 HTTP 链路同步发 Kafka。

不能把「每次调用都新建参与记录」作为新用例默认流程。[DrawRequestIdempotencyTest.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/domain/DrawRequestIdempotencyTest.java) 验证首次、已消费回放、未完成 take 复用与异常释放锁；它使用 mock，不能证明数据库唯一约束部署正确。

## Kafka、调度与持久状态

[KafkaProducer.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/mq/producer/KafkaProducer.java) 以 orderId 字符串作为消息 key，沿用 lottery_invoice 与 lottery_strategy_award_stock 的消息契约。库存监听器在条件扣减事务中写唯一账本，通过订单 CAS 持久化结算终态和发奖义务后 ack。库存不足进入 BLOCKED 等待对账。发奖监听器按 supportType 选取处理器，当前模拟渠道在同一 SQL 写实际发奖与消息终态，持久化后 ack。消费异常记录并传播。

[LotteryScheduledTasks.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryScheduledTasks.java) 的三个独立固定延迟任务分别扫描到期活动、库存义务与发奖义务。活动预热与状态更新由原生命周期服务执行，库存与发奖复用有界投递池。TX2 写库存首发代数，提交后提交实时库存投递。库存完成 CAS 写发奖首发代数，只有更新成功的消费者提交实时发奖。首发到期后仍在途时允许补偿重投，消费者依赖原账本和发奖 CAS 幂等。

[DrawOrder_Mapper.xml](../../../lottery-interfaces/src/main/resources/mybatis/mapper/DrawOrder_Mapper.xml) 按 retry_at 索引查询到期义务，补偿领取 CAS 递增事件代数并设置租期，发送回调只更新当前代数和非终态。发送失败退避，发送成功保留 SENT 超时重投。迟到回调不允许覆盖 COMPLETE 或 BLOCKED。SQL 异常向上传播，零行状态 CAS 表示当前条件失效。库存与发奖独立记录时间，避免业务 update_time 改动推迟补偿。历史误写发奖终态在 V7 一次性修复。

复用 [MqCallbackStateTest.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/mq/MqCallbackStateTest.java) 和 [FulfillmentEventStatusCasManualIT.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/manual/FulfillmentEventStatusCasManualIT.java) 验证迟到回调、重试代数与 SQL CAS。真实 Kafka 停机、重复消息和 HTTP 链路由 MqMiddlewareE2eManualIT、FinalBusinessE2eManualIT 验证。投递协议、迁移及人工对账见 [订单 Outbox](../../../docs/outbox-delivery.md)。

抽奖请求归属查询失败时中断。其他未完成请求返回 D007 与 pendingRequestId，无归属记录返回 D008。短锁过期引发唯一键或消费 CAS 竞争时，根据已提交 take 或订单回放。完整恢复与部署前提见 [接口说明](../../../docs/draw-request-idempotency.md)。
