# 代码质量与验证

## 本项目约定

编译目标 Java 8，保留现有 Spring Boot 2.3 和 javax.* API；不要使用 record、var 或 Java 9 之后的集合工厂。局部沿用现有显式 getter/setter、@Resource 注入和 @Service/@Repository/@Component；不要为初始化或小改动引入 Lombok、统一注入重构或新增框架。

优先使用能表达业务意图的命名。用简体中文注释说明非显然的事务边界、账本前置条件和补偿条件。遵循 [AGENTS.md](../../../AGENTS.md) 的根因优先原则，不为通过测试改变契约或增加样例特例，不做无依据的过度防御。

common 只承载真实共享契约，优先复用 [Result.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Result.java)、[Constants.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Constants.java) 与 [PageRequest.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/PageRequest.java)。不要把仅某个业务域使用的算法或 DTO 提升到 common。

## 已有测试及覆盖边界

| 测试 | 当前验证内容 | 环境 |
| --- | --- | --- |
| [WeightedRandomSelectorTest.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/domain/WeightedRandomSelectorTest.java) | 微精度、边界采样、NO_PRIZE、负概率与总和超限 | JUnit 4，无外部服务 |
| [DrawRequestIdempotencyTest.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/domain/DrawRequestIdempotencyTest.java) | 首次编排、已消费回放、未完成 take 复用、异常释放锁 | Mockito / ReflectionTestUtils，无外部服务 |
| [RedisActivityDrawProcessGuardTest.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/infrastructure/lock/RedisActivityDrawProcessGuardTest.java) | 锁冲突、获取失败、token 与 Lua compare-delete 调用 | Mockito，无真实 Redis |
| CacheTransactionInvalidationTest、SnowflakeNodeConfigurationTest | 缓存提交后失效、同宿主机显式节点配置 | 默认单元门禁 |
| WebPersistenceBoundaryTest、ActiveSpecLinksTest | Web 不直接依赖 DAO/PO，现行规范的本地文件链接有效 | 默认单元门禁 |
| RedisStockFailureManualIT | 真实 Lua 并发扣减、缺 Key、原子回补及延期不重建记录 | 显式指定 `-Dlottery.it.redis.port=16379` |
| PerformanceSqlManualIT | 使用生产 Mapper 验证 V9 分桶与存量单行共存、并发库存、重复结算及账本回滚 | 专用 MySQL |
| FulfillmentEventStatusCasManualIT | 订单须凭匹配账本完成、并发仅一方登记发奖、BLOCKED 与到期领取 | 专用空 MySQL（13319） |
| FinalBusinessE2eManualIT、DemoAccountsManualIT | HTTP、八桶库存、真实 Broker、幂等回放和模拟发奖终态 | 独立 Docker Compose |

## Maven 命令与适用范围

根 [pom.xml](../../../pom.xml) 的 Surefire 2.20 默认排除 ManualIT、test/dao、test/interfaces 和 test/infrastructure/cache 下的测试。业务改动需要单元门禁时从根仓库运行：

```bash
# 在根 Maven reactor 编译并执行默认单元测试。
mvn clean test
```

默认八桶快速回归从仓库根目录执行，脚本启动专用 Compose、建 Topic 并选择关键用例：

```bash
bash lottery-interfaces/src/test/resources/mq-e2e/verify.sh
```

MySQL、Redis、NameServer 和 Broker 分别绑定到 13309、16379、19876、19911。初始化器在 DROP 前核验专用 MySQL 的 Docker 标签和端口，测试只重建该项目的库。BLOCKED 用例在消费者停止时注入分歧，等待真实 Broker 重复消息处理结束后再核对最终状态。

完整 HTTP/中间件回归在同一专用栈上执行：

```bash
mvn -pl lottery-interfaces -am \
  -Dtest=FinalBusinessE2eManualIT,DemoAccountsManualIT,DrawAdmissionRedisManualIT,RedisStockFailureManualIT \
  -Dlottery.it.redis.port=16379 -Dsurefire.failIfNoSpecifiedTests=false test
```

`surefire.failIfNoSpecifiedTests=false` 允许没有匹配测试的其他 Maven 模块继续；匹配用例失败仍使构建失败。SQL 专项和历史迁移测试需要各自的 fixture，不能用通配符一起指向业务库。

## 按改动选择验证

库存或消息改动须检查真实订单、库存、账本和发奖终态。单元测试、直接调用消费者、实际 Broker 停机和进程退出分别记录，不能互相替代。事务消息回查及进程退出复现见[事务消息报告](../../../docs/rocketmq-transactional-report.md)。请求归属 SQL 专项按[抽奖幂等接口说明](../../../docs/draw-request-idempotency.md)准备专用空 MySQL；其中 Redis 与抽样由测试替代。性能验证使用[独立压测栈](./performance-testing.md)，历史吞吐结果只用于对应版本。
