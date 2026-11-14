# 错误处理与已有偏差

## 业务结果和异常

[Constants.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Constants.java) 与 [Result.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Result.java) 表示业务结果。DRAW_IN_PROGRESS、LOSING_DRAW、NO_UPDATE、INDEX_DUP 等已有返回码描述特定业务状态，不应统一转换为系统异常，也不能把不确定的系统错误转换成中奖或幂等成功。

[ActivityPrizeSnapshotFactory.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/draw/service/snapshot/ActivityPrizeSnapshotFactory.java) 对非法概率显式抛异常；[RedisActivityDrawProcessGuard.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/lock/RedisActivityDrawProcessGuard.java) 获取锁失败记录 error 并带 cause 抛出；[LotteryController.java](../../../lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java) 在 HTTP 边界记录标识与异常堆栈后返回错误 DTO。新错误优先在实际责任层暴露，保持原 cause 便于定位。

## 有依据的补偿

TX1 的个人次数与参与记录在数据库内一起回滚，不涉及 Redis 回补；[ActivityProcessImpl.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java) 对明确的下单非成功业务码回补奖品库存。订单仓储的奖品异常回补依据下述事务完成状态，不捕获任意异常就加库存。[ActivityPrizeRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityPrizeRepository.java) 用唯一账本保证同订单结算可重放。

## 预扣后事务失败契约

1. **范围**：奖品预扣后创建订单。活动没有总库存，参与事务不再使用该回补工具。数据库错误不能解释成未中奖或静默成功。
2. **签名**：基础设施内部 `StockReservationTransactions.execute(TransactionTemplate, TransactionCallback<T>, Runnable recover, String reservation)` 观察 Spring `afterCompletion`；不改变领域 Repository 的业务接口。
3. **契约**：正常业务拒绝返回后仍由原编排回补；抛异常时仅事务尚未开始或 STATUS_ROLLED_BACK 执行一次回补。提交已完成或 STATUS_UNKNOWN 不加库存，输出关联标识和对账日志。
4. **错误**：原 SQL/事务异常原样抛出；回补再失败时作为 suppressed 附加并记 error，不能覆盖首个根因，也不能宣布回补成功。缺失库存不被重建。
5. **场景**：订单 INSERT 失败且成功回滚，奖品余量 10→9→10、take 仍未消费；原请求再试成功后余量 9。COMMIT/ROLLBACK 连接故障导致结果未知时保留预留，需按数据库持久事实对账。JVM 预扣后崩溃仍不在本地异常补偿保障内。
6. **验证**：StockReservationTransactionsTest 覆盖回滚、开始失败、提交未知、回滚失败、双重故障及业务拒绝；FinalBusinessE2eManualIT 使用真实 MySQL trigger 令 take/order INSERT 失败，分别断言 TX1 次数回滚、TX2 奖品只回补一次和同请求恢复。
7. **错误与正确**：错误是上层 `catch(Exception)` 无条件 INCR，或者只处理非成功业务码而漏掉 SQL 异常。正确是事务层观察结果，确认可回补后执行，结果未知显式留给对账。

消息消费者的异常日志与再抛出是交还 Kafka 重投的处理路径，Outbox 使用明确的数据库状态恢复投递。修改 ack、异常或状态终点时同时检查[应用规范](./application-guidelines.md)。

## 现存行为不作为新增代码范例

| 源码 | 现有行为与边界 |
| --- | --- |
| [ActivityProcessImpl.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java) 的 findTakeByRequestId | 查询异常记录 error 并传播，禁止把 SQL 失败解释为无参与记录；消费 CAS 零更新回读最终结果 |
| [ActivityPrizeRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityPrizeRepository.java) 的库存回补 | 原子检查现存 Key 后回补；失败抛出，缺失不创建。异常可见不等于补偿必达，Redis 不可用仍需对账 |
| [TwoLevelCacheService.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/cache/TwoLevelCacheService.java) | 部分缓存写入、失效异常只记录日志；须与事实存储或库存错误区分 |
| [StockSettlementListener.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/StockSettlementListener.java) 与 [DrawOrder_Mapper.xml](../../../lottery-interfaces/src/main/resources/mybatis/mapper/DrawOrder_Mapper.xml) | 库存分歧写 BLOCKED 并告警；持久化失败不 ack。FAIL 保留发送重试，对账后显式释放 BLOCKED 原订单 |
| [AbstractAwardFulfillmentHandler.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/award/service/fulfillment/impl/AbstractAwardFulfillmentHandler.java) | 本地 CAS 零更新也返回 SUCCESS；这仅是当前模拟渠道行为，不证明真实外部发奖已完成 |

初始化时记录的现状由后续任务持续修正；库存分歧、启动故障验证见[历史报告](../../../docs/final-correctness-e2e.md)，后续库存与事务异常修复见[本轮评估](../../../docs/failure-review-20261024.md)。

## 项目要求

以 [AGENTS.md](../../../AGENTS.md) 的根因优先、错误透明、最小修复为准。禁止静默吞错、针对单个样例的特殊规则和没有证据的 fallback、降级、兼容或重试。新增处理分支必须有需求、接口契约或真实失败案例，说明触发条件、适用边界与验证覆盖；不要为基本不可能出现的情况反复添加防御。
