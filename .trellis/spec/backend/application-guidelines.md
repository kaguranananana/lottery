# 应用编排、消息与任务

## 同步抽奖

[ActivityProcessImpl.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java) 的 doDrawProcess 是编排入口。先获得活动与用户维度的短锁，再按 requestId 查 take：已消费回放原订单或未中奖结果；未消费继续原 take；不存在才调用参与服务。锁在 finally 中携带原 token 释放。

未中奖直接消费 take。命中奖品时同步发送 Half Message，确认后在独立的 TX2 中锁定 take、登记 Redis 预扣、写入订单。售罄提交消费参与并回滚消息；基础设施异常保留原 take 供原 requestId 重试。

不能把「每次调用都新建参与记录」作为新用例默认流程。[DrawRequestIdempotencyTest.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/domain/DrawRequestIdempotencyTest.java) 验证首次、已消费回放、未完成 take 复用与异常释放锁；它使用 mock，不能证明数据库唯一约束部署正确。

## RocketMQ、调度与持久状态

库存使用事务 Topic，事务回查读取已提交订单；未到截止、参与锁被占用或数据库无法确认时返回 UNKNOWN。到期后取得参与锁并复查订单，确认无订单才回滚。TX2 取得同一行锁后检查数据库时间，避免迟到回调在回滚决定后创建订单。生产者启动时主动向 Broker 注册稳定的生产者组。

库存消费者先在 TX3 写唯一账本、条件扣减库存，再用订单 CAS 完成结算并登记发奖义务。库存不足进入 BLOCKED。发奖消费者按订单重读快照，当前模拟渠道在同一 SQL 写实际发奖和事件终态。

定时任务推进活动、对账孤立预扣与超时库存、重投未完成发奖。订单领取按 retry_at 索引及到期 CAS，业务终态由消费者持久化。库存人工修复后显式释放 BLOCKED。 [事务消息与复现](../../../docs/rocketmq-transactional-report.md)

抽奖请求归属查询失败时中断。其他未完成请求返回 D007 与 pendingRequestId，无归属记录返回 D008。短锁过期引发唯一键或消费 CAS 竞争时，根据已提交 take 或订单回放。完整恢复与部署前提见 [接口说明](../../../docs/draw-request-idempotency.md)。
