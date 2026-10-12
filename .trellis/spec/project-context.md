# 项目上下文

## 技术与运行单元

根 [pom.xml](../../pom.xml) 聚合五个模块，编译目标为 Java 8，使用 Spring Boot 2.3.5.RELEASE。持久化使用 MyBatis 2.1.4、MySQL；并发库存和锁使用 Redis；异步结算与发奖使用 Spring Kafka；调度使用 XXL-Job 2.3.0；策略快照使用 Caffeine。依赖版本以 Maven 文件为准，任务中不要顺带升级框架或 JDK。

启动入口为 [LotteryApplication.java](../../lottery-interfaces/src/main/java/com/anontokyo/lottery/LotteryApplication.java)。[lottery-interfaces/pom.xml](../../lottery-interfaces/pom.xml) 将应用打包为 WAR；其它业务模块提供 JAR。

| 模块 | 所有权 | Maven 项目内依赖 |
| --- | --- | --- |
| lottery-common | Result、枚举、RedisKey、分页值对象 | 无 |
| lottery-domain | 参与资格、概率模型、活动状态机、订单与发奖抽象、Repository 和 Guard 接口 | common |
| lottery-application | 用例编排、Kafka 收发、XXL-Job | domain |
| lottery-infrastructure | Repository 实现、DAO/PO、事务、路由、Redis 与缓存 | domain |
| lottery-interfaces | 启动装配、HTTP DTO、Mapper XML、当前全部测试 | application、domain、infrastructure |

领域模块现仍使用 Spring 注解，包含 Redis 配置，并在 [lottery-domain/pom.xml](../../lottery-domain/pom.xml) 中保留路由依赖。Java 源码中的路由组件导入与 TransactionTemplate 集中在 infrastructure；活动部署仍有领域服务的声明式事务。不能把当前架构描述成完全脱离框架的领域层。

## 抽奖与异步链路

1. [LotteryController.java](../../lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java) 校验请求，将 userId 转为内部 uId，调用抽奖用例。
2. [ActivityProcessImpl.java](../../lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java) 获取用户与活动维度的短锁，按 requestId 查询参与记录；已消费则回放，未消费则继续原 take。
3. [BaseActivityPartake.java](../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/BaseActivityPartake.java) 编排参与资格、Redis 活动库存、数据库次数扣减和参与记录创建。
4. [DrawExecutor.java](../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/strategy/service/draw/impl/DrawExecutor.java) 读取策略快照并加权采样。未中奖消费 take；中奖预扣奖品 Redis 库存并创建订单。
5. [OrderRepository.java](../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java) 在用户分片事务内消费 take 并插入订单，订单包含库存 Outbox 状态；同步响应不等待 Kafka 结算或第三方发奖。
6. [LotteryXxlJob.java](../../lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryXxlJob.java) 扫描 Outbox，库存消费者通过全局库账本结算后发送发奖消息，发奖消费者按奖品类型选择处理器。

## 事实来源与部署前提

MySQL 保存参与记录、订单与库存结算事实；Redis 锁用于并发控制，不能证明业务已完成。全局库为 lottery，用户分片库为 lottery_01/02；订单表每库四张，参与与次数表仅分库。路由与迁移边界见[数据库规范](./backend/database-guidelines.md)。

[application.yml](../../lottery-interfaces/src/main/resources/application.yml) 的本地配置为 HTTP 8080、MySQL 13306、Redis 6379、Kafka 9092；XXL-Job admin 为 7397，executor 为 9999。运行前核对连接配置与执行器日志目录。初始化 Trellis 不启动服务、不执行 SQL，也不改变运行配置。

构建入口、测试边界和本机路由依赖见[质量规范](./backend/quality-guidelines.md)。性能材料为 doc/jmeter 下的脚本与输入数据，不能据此宣称已测得生产 QPS。

## 已知边界

- 发奖处理器模拟渠道调用并写本地状态；真实外部发奖尚未实现，本地 CAS 不证明外部副作用恰好一次。
- Redis 库存丢失按不可售处理，需要预热或对账；配置缓存与库存的错误策略不同。
- 快照缓存没有跨实例广播失效，TTL 内可能仍读到旧快照。
- 本地 Kafka 为单副本、acks=1，不代表生产级消息可靠性。
- 请求查询异常中断、库存 BLOCKED 对账与 FAIL 重试和 SENT 恢复边界见[错误处理](./backend/error-handling.md)与[应用规范](./backend/application-guidelines.md)。

分析基于直接源码与测试阅读；本机没有可用 GitNexus CLI 或 GitNexus/ABCoder MCP，本次未生成额外索引。以上为源码事实或已有文档说明，不代表本次已执行业务测试。
