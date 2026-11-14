# 项目上下文

## 技术与运行单元

根 [pom.xml](../../pom.xml) 聚合五个模块，编译目标为 Java 8，使用 Spring Boot 2.3.5.RELEASE。持久化使用 MyBatis 2.1.4、MySQL；并发库存和锁使用 Redis；异步结算与发奖使用 RocketMQ Client 5.3.4；调度使用 Spring；活动奖池快照使用 Caffeine。依赖版本以 Maven 文件为准，任务中不要顺带升级框架或 JDK。

启动入口为 [LotteryApplication.java](../../lottery-interfaces/src/main/java/com/anontokyo/lottery/LotteryApplication.java)。[lottery-interfaces/pom.xml](../../lottery-interfaces/pom.xml) 将应用打包为 WAR；其它业务模块提供 JAR。

| 模块 | 所有权 | Maven 项目内依赖 |
| --- | --- | --- |
| lottery-common | Result、枚举、RedisKey、分页值对象 | 无 |
| lottery-domain | 参与资格、概率模型、活动状态机、订单与发奖抽象、Repository 和 Guard 接口 | common |
| lottery-application | 用例编排、RocketMQ 收发、Spring 定时任务 | domain |
| lottery-infrastructure | Repository 实现、DAO/PO、本地事务、Redis 与缓存 | domain |
| lottery-interfaces | 启动装配、HTTP DTO、Mapper XML、当前全部测试 | application、domain、infrastructure |

领域模块仍使用 Spring 注解并包含 Redis 配置；TransactionTemplate 集中在 infrastructure，活动部署使用领域服务的声明式事务。不能把当前架构描述成完全脱离框架的领域层。

## 抽奖与异步链路

1. [LotteryController.java](../../lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java) 校验请求，将 userId 转为内部 uId，调用抽奖用例。
2. [ActivityProcessImpl.java](../../lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java) 获取用户与活动维度的短锁，按 requestId 查询参与记录；已消费则回放，未消费则继续原 take。
3. [BaseActivityPartake.java](../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/service/partake/BaseActivityPartake.java) 校验活动状态与时间，编排 TX1：同一 MySQL 事务内条件扣减个人次数与创建参与记录。活动不再有总库存。
4. [DrawExecutor.java](../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/draw/service/draw/impl/DrawExecutor.java) 读取奖池快照并加权采样。未中奖消费 take；命中奖品时发送库存 Half Message。
5. [OrderRepository.java](../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java) 在 Half Message 确认后执行 TX2，锁定 take、登记 Redis 预扣并插入订单。库存消费与本地发奖异步处理。
6. [LotteryScheduledTasks.java](../../lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryScheduledTasks.java) 扫描到期义务和 Redis 未决预扣，库存消费者通过唯一账本结算后发送发奖消息，发奖消费者按奖品类型选择处理器。

## 事实来源与部署前提

MySQL 保存参与记录、订单与库存结算事实；Redis 锁用于并发控制，不能证明业务已完成。单一 MySQL 库中的七张表承载这些事实，HTTP 入口先校验身份并限流。表结构与迁移边界见[数据库规范](./backend/database-guidelines.md)。

[application.yml](../../lottery-interfaces/src/main/resources/application.yml) 的本地配置为 HTTP 8080、MySQL 13306、Redis 6379、RocketMQ NameServer 9876。运行前核对连接配置。初始化 Trellis 不启动服务、不执行 SQL，也不改变运行配置。

构建入口和测试边界见[质量规范](./backend/quality-guidelines.md)。性能工具位于 doc/performance，不能据此宣称已测得生产 QPS。

## 已知边界

- 发奖处理器模拟渠道调用并写本地状态；真实外部发奖尚未实现，本地 CAS 不证明外部副作用恰好一次。
- 奖品 Redis 库存缺失显式报错并保留原 take；运行期奖池缺失须对账，不能用滞后 MySQL 值回填。配置缓存与库存的错误策略不同。
- 快照缓存没有跨实例广播失效，TTL 内可能仍读到旧快照。
- 隔离 RocketMQ 采用单 Broker 同步刷盘，覆盖进程恢复，生产高可用需单独验证。
- 请求查询异常中断、库存 BLOCKED 与超时义务恢复边界见[错误处理](./backend/error-handling.md)与[应用规范](./backend/application-guidelines.md)。

分析基于直接源码与测试阅读；本机没有可用 GitNexus CLI 或 GitNexus/ABCoder MCP，本次未生成额外索引。以上为源码事实或已有文档说明，不代表本次已执行业务测试。
