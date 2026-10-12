# 代码质量与验证

## 本项目约定

编译目标 Java 8，保留现有 Spring Boot 2.3 和 javax.* API；不要使用 record、var 或 Java 9 之后的集合工厂。局部沿用现有显式 getter/setter、@Resource 注入和 @Service/@Repository/@Component；不要为初始化或小改动引入 Lombok、统一注入重构或新增框架。

生成代码须加入简体中文注释，关键逻辑逐行解释。事务、路由清理、库存扣减、ack 与补偿注释应说明每一步的实际语义。遵循 [AGENTS.md](../../../AGENTS.md) 的根因优先原则，不为通过测试改变契约或增加样例特例，不做无依据的过度防御。

common 只承载真实共享契约，优先复用 [Result.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Result.java)、[Constants.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/Constants.java) 与 [PageRequest.java](../../../lottery-common/src/main/java/com/anontokyo/lottery/common/PageRequest.java)。不要把仅某个业务域使用的算法或 DTO 提升到 common。

## 已有测试及覆盖边界

| 测试 | 当前验证内容 | 环境 |
| --- | --- | --- |
| [WeightedRandomSelectorTest.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/domain/WeightedRandomSelectorTest.java) | 微精度、边界采样、NO_PRIZE、负概率与总和超限 | JUnit 4，无外部服务 |
| [DrawRequestIdempotencyTest.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/domain/DrawRequestIdempotencyTest.java) | 首次编排、已消费回放、未完成 take 复用、异常释放锁 | Mockito / ReflectionTestUtils，无外部服务 |
| [RedisActivityDrawProcessGuardTest.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/infrastructure/lock/RedisActivityDrawProcessGuardTest.java) | 锁冲突、获取失败、token 与 Lua compare-delete 调用 | Mockito，无真实 Redis |
| [StockSettlementIdempotencyManualIT.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/manual/StockSettlementIdempotencyManualIT.java) | 手写 JDBC 结算 SQL 与 Redis 并发操作 | 本机 MySQL/Redis；不是生产 Repository 或 Kafka 全链路测试 |
| 其它 ManualIT 及 legacy DAO/API 测试 | Spring 上下文与真实中间件场景 | 按具体用例准备环境 |

[StockSettlementIdempotencyManualIT.java](../../../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/manual/StockSettlementIdempotencyManualIT.java) 依赖预置策略 10001/奖品 C 的库存，并有未完成的测试数据准备注释；不能将它视为已自动部署并覆盖 Outbox 的可靠门禁。其并发线程内吞异常和裸 SQL 也不是新测试的推荐模式。

## Maven 命令与适用范围

根 [pom.xml](../../../pom.xml) 的 Surefire 2.20 默认排除 ManualIT、test/dao、test/interfaces 和 test/infrastructure/cache 下的测试。业务改动需要单元门禁时从根仓库运行：

```bash
# 在根 Maven reactor 编译并执行默认单元测试。
mvn clean test
```

已有手工集成运行方式见 [README.md](../../../README.md)。从根 reactor 精确选择手工用例时，没有匹配测试的模块需要明确允许为空：

```bash
# 指定手工集成测试；先按所选场景准备数据库与中间件。
mvn test -Dtest='*ManualIT' -Dsurefire.failIfNoSpecifiedTests=false
```

该选项只允许 common/domain 等没有匹配测试的模块继续，不改变匹配用例的失败结果。手工集成会写数据，只在明确要验证该链路的任务中运行。

路由依赖为 cn.bugstack.middleware:db-router-spring-boot-starter:1.0.4。若本机 Maven 无该制品，先检查相邻 db-router-spring-boot-starter 仓库的实际 artifact/version 与本地安装，再解决依赖；不能改 Lottery 版本号来绕过解析失败。

## 按改动选择验证

纯 Trellis 文档与配置变更检查索引、源码链接、无残留模板，以及 get_context/task 脚本的正常输出；不自动扩大为业务测试或重构。业务改动根据所触及的契约选择既有单测；涉及真实路由、SQL、库存或 Kafka 时，mock 结果不能替代对应环境验证。报告执行命令、实际结果与未覆盖部分，不复制泛化检查清单。

新增 DrawRequestOwnershipManualIT 使用专用空 MySQL 实例，按 [抽奖幂等接口说明](../../../docs/draw-request-idempotency.md) 的单独命令运行并传入 lottery.it.mysql；不能把它混同为默认业务实例上的全环境 ManualIT。该测试执行生产 Mapper、路由切面、Repository 和事务，真实数据库约束测试与 Redis/抽样 Mock 的边界须同时报告。

消息状态测试：MqCallbackStateTest 进入默认 mvn test 门禁；MqStateCasManualIT 须传入专用空 MySQL URL，测试自行建删 lottery_mq_cas_it，不装配分片插件。真实 Mapper/Repository CAS、并发和超时 SQL 已与 Kafka/Redis Mock 边界区分，见[消息修复说明](../../../docs/kafka-mq-state-cas.md)。

MqMiddlewareE2eManualIT 在独立 Docker compose 中运行真实 MySQL 8、Redis、Kafka 与生产 Spring 组件，覆盖两库八分表、库存账本事务、实际 Kafka ack、迟到通知、重复消息与真实 broker 停机恢复。测试仅通过生产者装饰器延后 Future 通知，不使用中间件 Mock；注入失败与真实 broker 失败必须分开报告。会重建测试端口 13309 的三个 fixture schema，运行步骤与已确认的停止宽限期要求见[端到端验证记录](../../../docs/kafka-middleware-e2e.md)。

最终验证增加 FinalBusinessE2eManualIT：真实随机端口 HTTP、活动配置事务、Redis、两库八张订单表、Kafka 与库存账本，不替换任何中间件。测试包含 Redis 停机、ACL 第二个 Key 写入失败、SQL 账本表临时改名；异常 ACK 用例直接调用生产消费者，恢复后通过真实 Kafka 完成。MqMiddlewareE2eManualIT 在 DROP 前检查 Docker 标签及端口；两者只能在专用 compose 13309 实例运行。SQL-only 集成测试使用另一专用空 MySQL（13319），结果与命令见[最终报告](../../../docs/final-correctness-e2e.md)。

性能压测使用doc/performance独立栈与启动器；环境、实际接口与容量证据边界见[性能压测契约](./performance-testing.md)。历史JMeter/SQL不能直接视为当前业务的可执行基线。
