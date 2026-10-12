# 应用编排、消息与任务

## 同步抽奖

[ActivityProcessImpl.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java) 的 doDrawProcess 是编排入口。先获得活动与用户维度的短锁，再按 requestId 查 take：已消费回放原订单或未中奖结果；未消费继续原 take；不存在才调用参与服务。锁在 finally 中携带原 token 释放。

未中奖需要消费 take；中奖创建订单。订单服务返回非成功业务码时，当前编排回补已预扣奖品库存。同步方法完成后返回结果，库存结算通过订单 Outbox 异步驱动，不在 HTTP 链路同步发 Kafka。

不能把「每次调用都新建参与记录」作为新用例默认流程。[DrawRequestIdempotencyTest.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/domain/DrawRequestIdempotencyTest.java) 验证首次、已消费回放、未完成 take 复用与异常释放锁；它使用 mock，不能证明数据库路由与唯一约束部署正确。

## Kafka 契约

[KafkaProducer.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/mq/producer/KafkaProducer.java) 以 orderId 字符串作为消息 key，Fastjson 将 InvoiceVO 或 AwardStockSettlementVO 序列化为字符串。已有 topic 是 lottery_invoice 与 lottery_strategy_award_stock。变更消息字段时同时检查生产者、消费者、领域 VO 和数据库映射。

[LotteryStrategyAwardStockListener.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryStrategyAwardStockListener.java) 先检查订单状态与订单存在性，通过 settleAwardStock 结算；成功后写库存 COMPLETE、发送发奖消息并手动 ack。异常记录堆栈并抛出。库存条件扣减失败时写 FAIL、记录对账告警并 ack，该分支实际仍会被库存 Outbox 扫描重投。

[LotteryInvoiceListener.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryInvoiceListener.java) 在启动时按 supportType 建立处理器注册表，重复类型直接抛异常。消费时先看数据库发奖状态，再用 Redis processing 锁减少并发；没有 handler 或处理失败则抛异常。当前处理器为模拟渠道，详见[领域规范](./domain-guidelines.md)。

## XXL-Job 的已有职责

| Job 名 | 范围和参数 |
| --- | --- |
| lotteryActivityStateJobHandler | 活动 CAS 转换与启动预热；CAS 成功后才预热库存 |
| awardStockSettlementOutboxJobHandler | 参数为分库编号，如 1 或 1,2；遍历该库四张订单表，批次来自配置，当前为 200 |
| lotteryOrderMQStateJobHandler | 参数为分库编号列表；发奖补偿每张表最多扫描 100 条 |

源码在 [LotteryXxlJob.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryXxlJob.java)。扫描通过领域接口进入仓储显式分片遍历，不能改成每实例 @Scheduled 扫描全量用户表。

## 消息状态 CAS 契约

### 1. 范围与触发

库存和发奖发送回调可能迟于消费完成，订单预读与 Redis marker 均不能约束后续数据库写入。状态保护落在 [UserStrategyExport_Mapper.xml](../../../lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml) 的原子条件 UPDATE，保留现有分片与事务边界。

### 2. 签名

`IOrderRepository`、`IAwardOrder` 保留 `void updateStockMqState(String uId, Long orderId, Integer stockMqState)` 与 `void updateInvoiceMqState(String uId, Long orderId, Integer mqState)`，新增 `void markStockSettlementFailed(String uId, Long orderId)` 表达消费侧库存失败。对应 DAO 返回 int 影响行数，均按 uId 路由；扫描仍由仓储显式设置分片。

### 3. 数据与补偿契约

MQState：INIT=0、COMPLETE=1、FAIL=2、SENT=3。消息 key、VO 字段及数据库列保持原样，无结构迁移。两个发送入口（库存消费后发送、发奖补偿）统一成功写 SENT。发奖消费者确认本地 grant_state=1 后写 mq_state=1，再 ack；已发奖的重放分支也收口消息状态。

库存补偿扫 INIT/FAIL 及 update_time 超过 10 分钟的 SENT。发奖补偿要求库存已完成、grant_state 未完成，扫描 FAIL、create_time 超过 30 分钟的 INIT、update_time 超过 10 分钟的 SENT。旧补偿发送成功曾写 mq_state=1，所以未发奖的此类历史行也按 10 分钟超时恢复；这只针对已证实的历史语义，不向其它模块扩展兼容规则。

重复或被拒绝事件不更新 update_time，避免迟到回调无限推迟补偿。超时重投后相同 SENT 也不刷新时间，消费者未完成时后续扫描仍可重投；这遵循 at-least-once，实际结算去重依赖库存账本，当前发奖为本地模拟渠道 CAS。

### 4. 状态与错误矩阵

| 事件 | 允许推进 | 被忽略的状态 |
| --- | --- | --- |
| 发送成功 | INIT/FAIL → SENT | SENT、COMPLETE |
| 发送失败 | INIT → FAIL | FAIL、SENT、COMPLETE |
| 库存消费完成 | INIT/FAIL/SENT → COMPLETE | COMPLETE |
| 发奖消费完成 | INIT/FAIL/SENT → COMPLETE，且 grant_state=1 | COMPLETE 或 grant_state 未完成 |
| 库存消费失败（专用入口） | INIT/SENT → FAIL | FAIL、COMPLETE |

数据库异常向上传播；消费者异常不 ack，由 Kafka 重投。条件 UPDATE 零行表示重复或不满足事件源状态，不等同 SQL 异常，也不能用来证明订单一定存在。库存分歧 FAIL 仍被 Outbox 扫描并记录人工对账告警，不是停止重试的终态。

### 5. 正常、乱序与失败场景

正常：INIT → SENT → COMPLETE。消费先到：INIT → COMPLETE 后所有发送通知均不再改行。重试：INIT → FAIL → SENT，旧失败通知被忽略。业务结算失败：SENT → FAIL → SENT → COMPLETE。发奖发送成功与实际发奖之间保持 SENT，不允许用发送成功替代消费事实。

### 6. 必需测试与实际边界

[MqCallbackStateTest.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/mq/MqCallbackStateTest.java) 验证应用入口语义、重复发奖修复、异常阻止 ack，以及两个发奖发送入口的状态一致性（Mock）。[MqStateCasManualIT.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/manual/MqStateCasManualIT.java) 在专用空 MySQL 执行生产 Mapper/Repository，覆盖矩阵、消费者先完成、迟到成功/失败、多轮重试、真实 SQL 并发、重复事件不刷新时间、超时扫描与旧发奖状态恢复。后者不装配分片插件，Kafka Future、Redis guard 和库存业务结算被 Mock，不证明真实 Kafka、Redis 或八分表端到端结果。运行命令及本次结果见 [修复说明](../../../docs/kafka-mq-state-cas.md)。

[MqMiddlewareE2eManualIT.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/manual/MqMiddlewareE2eManualIT.java) 进一步使用真实 MySQL 8、Redis、Kafka 与两库八表、生产 Repository 和消费者验证完整 Outbox 到发奖链路，以及 broker 停机后的补偿恢复。Future 故障注入只延迟通知，消息仍真实发送；真实失败另由停机用例证明。外部调度 admin、HTTP 与外部发奖渠道未覆盖，见[端到端验证](../../../docs/kafka-middleware-e2e.md)。

### 7. 错误写法与正确写法

错误：`WHERE u_id=? AND order_id=?` 后无条件覆盖消息状态；在发送前查询 COMPLETE 并不能保护异步回调。

正确：发送成功更新在 WHERE 中加 `stock_mq_state IN (0,2)`；完成事件允许从所有未完成状态收口；消费失败使用独立条件入口。不能只允许 INIT，否则 FAIL 重试无法推进，也不能让发送失败将 SENT 回退为 FAIL。

抽奖请求归属：参与查询失败中断，不允许降级为新请求。其他未完成请求返回 D007 与 pendingRequestId；无归属记录 D008。短锁过期造成参与唯一键或消费 CAS 竞争时，根据已提交 take/订单回放，不将零更新直接解释为未中奖。完整恢复与部署前提见 [接口说明](../../../docs/draw-request-idempotency.md)。
