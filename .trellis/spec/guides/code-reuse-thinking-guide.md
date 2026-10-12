# 现有能力复用

## 从已有所有权开始

新增内容前用 rg 搜索符号与调用方，优先延续真实抽象：

| 需求 | 现有入口 |
| --- | --- |
| 业务返回码、Redis key | [Constants.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Constants.java) / [Result.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Result.java) |
| 订单与参与 ID | [IdGenerator.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/support/ids/IdGenerator.java) / [SnowflakeIdGenerator.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/support/ids/SnowflakeIdGenerator.java) |
| 策略抽样 | [StrategySnapshotFactory.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/snapshot/StrategySnapshotFactory.java) / [WeightedRandomSelector.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/draw/WeightedRandomSelector.java) |
| 活动状态转换 | [ActivityLifecycleService.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/lifecycle/ActivityLifecycleService.java) |
| 抽奖并发控制 | [IActivityDrawProcessGuard.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/support/lock/IActivityDrawProcessGuard.java) / [RedisActivityDrawProcessGuard.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/lock/RedisActivityDrawProcessGuard.java) |
| 库存结算幂等 | [IStrategyRepository.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/repository/IStrategyRepository.java) 的 settleAwardStock |
| 发奖类型扩展 | [AwardFulfillmentHandler.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/award/service/fulfillment/AwardFulfillmentHandler.java) 与消费者注册表 |
| 补偿与调度 | [LotteryXxlJob.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryXxlJob.java) 与 Repository 的显式分片扫描 |

## 保持抽象与职责相称

相似代码承担相同业务语义才提取。抽奖锁有 owner token，发奖 processing 与 dispatch marker 的当前实现不同，不因为都使用 Redis 就强行合成一套锁。活动配置与策略快照的缓存层数也不同，不能为了统一 API 为活动增加 L1 或广播失效。

转换逻辑在职责所在层处理；业务码与枚举复用 common，业务域特有类型留在 domain。增加发奖类型实现已有 handler 接口并检查注册，不再包一层通用 Factory。看到历史空循环、重复导入或旧接口时，只在任务确实涉及的最小范围内处理，不借规范初始化清理业务代码。

引用以上能力后仍需读取实现，不照搬已记录的查询降级、缓存吞错或过时注释。新增 helper 应有重复语义或真实调用需求；能用已有方法表达的操作，不另建通用工具框架。
