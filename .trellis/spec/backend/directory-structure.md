# 目录与模块依赖

## 文件放置

| 位置 | 内容与实例 |
| --- | --- |
| interfaces 的 interfaces/web、dto | HTTP adapter：[LotteryController.java](../../../lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java)、[DrawRequest.java](../../../lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/dto/DrawRequest.java) |
| application 的 process、mq、worker | 用例、消息、调度：[ActivityProcessImpl.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java)、[LotteryXxlJob.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryXxlJob.java) |
| domain 的 activity、strategy、award | 按业务域组织 model、repository、service：[IStrategyRepository.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/repository/IStrategyRepository.java)、[DrawExecutor.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/draw/impl/DrawExecutor.java) |
| infrastructure 的 repository、dao、po、cache、lock、utils | 技术实现：[StrategyRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/StrategyRepository.java)、[RedisActivityDrawProcessGuard.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/lock/RedisActivityDrawProcessGuard.java) |
| interfaces 的 resources/mybatis/mapper | DAO 对应 SQL：[UserStrategyExport_Mapper.xml](../../../lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml) |
| common | 跨模块业务码、枚举与 key：[Constants.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Constants.java)、[Result.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Result.java) |
| interfaces 的 src/test | 当前单元测试与手工集成测试，详见[质量规范](./quality-guidelines.md) |

表中的简写对应 lottery-interfaces、lottery-application、lottery-domain、lottery-infrastructure、lottery-common。所有 Java 源码位于 com.anontokyo.lottery 根包下。现有命名有 uId、getuId() 和大写 deploy/Impl 等历史写法；使用真实接口签名，不为统一命名顺带重构。

## 职责与依赖边界

业务判定放在 domain，用例步骤与 Kafka/调度放在 application。domain 定义 Repository 和 Guard 接口，infrastructure 提供实现。Controller 负责输入、DTO 转换和调用用例，不直接操作 DAO、库存或发奖。

新业务逻辑通过领域接口访问存储，避免 application/domain 直接导入 infrastructure 的 PO 或 DAO。事务与手动路由以 [OrderRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java) 和 [UserTakeActivityRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/UserTakeActivityRepository.java) 为参考。

这是新改动的边界规则。现有 [ActivityDeployImpl.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/deploy/Impl/ActivityDeployImpl.java) 使用声明式事务，[RedisConfig.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/support/redis/RedisConfig.java) 位于 domain，Maven 保留部分技术依赖；本次初始化不调整这些组织。

Mapper XML 虽位于 interfaces，持久化职责仍属于 infrastructure。新增数据库字段须核对 DAO 方法、PO 属性、XML 参数与领域映射，不能仅按所在模块判断所有权。
