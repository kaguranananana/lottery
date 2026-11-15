# 错误处理与已有偏差

## 业务结果和异常

[Constants.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Constants.java) 与 [Result.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Result.java) 表示业务结果。DRAW_IN_PROGRESS、LOSING_DRAW、NO_UPDATE、INDEX_DUP 等已有返回码描述特定业务状态，不应统一转换为系统异常，也不能把不确定的系统错误转换成中奖或幂等成功。

[ActivityPrizeSnapshotFactory.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/draw/service/snapshot/ActivityPrizeSnapshotFactory.java) 对非法概率显式抛异常；[RedisActivityDrawProcessGuard.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/lock/RedisActivityDrawProcessGuard.java) 获取锁失败记录 error 并带 cause 抛出；[LotteryController.java](../../../lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java) 在 HTTP 边界记录标识与异常堆栈后返回错误 DTO。新错误优先在实际责任层暴露，保持原 cause 便于定位。

## 有依据的补偿

TX1 的个人次数与参与记录在 MySQL 内一起回滚，不涉及 Redis 库存。RocketMQ 确认 Half Message 后，[OrderRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java) 在独立 TX2 持参与行锁，登记 Redis 预扣并写订单。真实售罄提交参与消费并回滚消息；Redis 缺 Key 或 SQL 故障保留原请求供恢复。

## 预扣对账契约

TX2 异常、进程退出或原请求重试遗留的预扣都由订单仓储负责。持参与锁查已提交订单：有订单只清理预扣索引，无订单才按原标记回补，结果不能确认时保留记录。Redis 原子清理标记并回补一次；库存 Key 缺失时不重建。不能根据异常类型推定事务回滚，也不能在应用编排中直接加库存。

定时扫描单条失败时保留预扣和错误日志，将该条延后一分钟复查，让后续到期记录能被处理；已清理的记录不能被延期命令重建。原请求重试仍可按参与号立即核对。原事务异常保留，回补或延期失败作为附带异常记录。

真实 HTTP 回归在默认八桶模式验证 TX2 回滚后原请求可恢复、混合预扣能继续扫描，以及 TX3 已提交而订单 CAS 失败后重放只完成状态、不重复扣减。运行方式见[质量规范](./quality-guidelines.md)。

消息消费者的异常日志与再抛出是交还 RocketMQ 重投的处理路径，定时任务通过数据库持久义务恢复超时投递。修改 ack、异常或状态终点时同时检查[应用规范](./application-guidelines.md)。

## 现存行为不作为新增代码范例

| 源码 | 现有行为与边界 |
| --- | --- |
| [ActivityProcessImpl.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java) 的 findTakeByRequestId | 查询异常记录 error 并传播，禁止把 SQL 失败解释为无参与记录；消费 CAS 零更新回读最终结果 |
| [RedisStockReservations.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/utils/RedisStockReservations.java) 的库存回补 | 原子检查现存 Key 后回补；失败抛出，缺失不创建。异常可见不等于补偿必达，Redis 不可用仍需对账 |
| [TwoLevelCacheService.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/cache/TwoLevelCacheService.java) | 部分缓存写入、失效异常只记录日志；须与事实存储或库存错误区分 |
| [StockSettlementListener.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/StockSettlementListener.java) 与 [DrawOrder_Mapper.xml](../../../lottery-interfaces/src/main/resources/mybatis/mapper/DrawOrder_Mapper.xml) | 库存分歧写 BLOCKED 并告警；持久化失败不 ack。到期未完成义务保留重投；对账后显式释放 BLOCKED 原订单 |
| [AbstractAwardFulfillmentHandler.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/award/service/fulfillment/impl/AbstractAwardFulfillmentHandler.java) | 本地 CAS 零更新也返回 SUCCESS；这仅是当前模拟渠道行为，不证明真实外部发奖已完成 |

初始化时记录的现状由后续任务持续修正；库存分歧、启动故障验证见[历史报告](../../../docs/final-correctness-e2e.md)，后续库存与事务异常修复见[本轮评估](../../../docs/failure-review-20261024.md)。

## 项目要求

以 [AGENTS.md](../../../AGENTS.md) 的根因优先、错误透明、最小修复为准。禁止静默吞错、针对单个样例的特殊规则和没有证据的 fallback、降级、兼容或重试。新增处理分支必须有需求、接口契约或真实失败案例，说明触发条件、适用边界与验证覆盖；不要为基本不可能出现的情况反复添加防御。
