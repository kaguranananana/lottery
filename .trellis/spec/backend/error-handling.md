# 错误处理与已有偏差

## 业务结果和异常

[Constants.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Constants.java) 与 [Result.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Result.java) 表示业务结果。DRAW_IN_PROGRESS、LOSING_DRAW、OUT_OF_STOCK、NO_UPDATE、INDEX_DUP 等已有返回码描述特定业务状态，不应统一转换为系统异常，也不能把不确定的系统错误转换成中奖或幂等成功。

[StrategySnapshotFactory.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/snapshot/StrategySnapshotFactory.java) 对非法概率显式抛异常；[RedisActivityDrawProcessGuard.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/lock/RedisActivityDrawProcessGuard.java) 获取锁失败记录 error 并带 cause 抛出；[LotteryController.java](../../../lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java) 在 HTTP 边界记录标识与异常堆栈后返回错误 DTO。新错误优先在实际责任层暴露，保持原 cause 便于定位。

## 有依据的补偿

[BaseActivityPartake.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/BaseActivityPartake.java) 创建 take 失败时回补已预扣活动库存并传播异常；[ActivityProcessImpl.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java) 对明确的下单非成功结果回补奖品库存；[StrategyRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/StrategyRepository.java) 用唯一账本保证同订单结算可重放。这些补偿各有前置条件，不能扩展为捕获任何异常后重跑整个抽奖。

消息消费者的异常日志与再抛出是交还 Kafka 重投的处理路径，Outbox 使用明确的数据库状态恢复投递。修改 ack、异常或状态终点时同时检查[应用规范](./application-guidelines.md)。

## 现存行为不作为新增代码范例

| 源码 | 现有行为与边界 |
| --- | --- |
| [ActivityProcessImpl.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java) 的 findTakeByRequestId | 查询异常记录 error 并传播，禁止把 SQL 失败解释为无参与记录；消费 CAS 零更新回读最终结果 |
| [ActivityRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityRepository.java)、[StrategyRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/StrategyRepository.java) 的库存回补 | INCR 异常记录 error 后返回，主流程未必获知回补失败；不能据此声称补偿必达 |
| [TwoLevelCacheService.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/cache/TwoLevelCacheService.java) | 部分缓存写入、失效异常只记录日志；须与事实存储或库存错误区分 |
| [LotteryStrategyAwardStockListener.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/LotteryStrategyAwardStockListener.java) 与 [UserStrategyExport_Mapper.xml](../../../lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml) | 库存分歧写 BLOCKED 并告警；持久化失败不 ack。FAIL 保留发送重试，对账后显式释放 BLOCKED 原订单 |
| [AbstractAwardFulfillmentHandler.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/award/service/fulfillment/impl/AbstractAwardFulfillmentHandler.java) | 本地 CAS 零更新也返回 SUCCESS；这仅是当前模拟渠道行为，不证明真实外部发奖已完成 |

初始化时记录的现状由后续任务持续修正；最新库存分歧、启动故障及真实主链路验证见[最终报告](../../../docs/final-correctness-e2e.md)。其他缓存/回补边界仍按实际实现评估。

## 项目要求

以 [AGENTS.md](../../../AGENTS.md) 的根因优先、错误透明、最小修复为准。禁止静默吞错、针对单个样例的特殊规则和没有证据的 fallback、降级、兼容或重试。新增处理分支必须有需求、接口契约或真实失败案例，说明触发条件、适用边界与验证覆盖；不要为基本不可能出现的情况反复添加防御。
