# 2026-10-24 项目故障评估与修复

> 历史评估：本文中的活动库存预扣/回补属于当时实现，已于 2026-11-02 移除；奖品库存故障修复与补偿机制继续保留。当前业务和验证见 [业务简化记录](activity-stock-removal.md)。

本轮先审阅项目状态、近期记录及抽奖、参与、库存、订单、消息和缓存主链路，再按用户授权直接修复确认的问题。已有流程文档与 Agent 配置改动保留，没有改动业务数据库、提交或推送 Git。以下结果对应当前工作区，不能代替生产部署验证。

## 已修复

| 问题 | 修复前证据 | 本次处理 |
| --- | --- | --- |
| 奖品库存 Key 缺失被当成未中奖 | 真实 HTTP 期望系统错误 0001，实际 D001；参与被消费 | 缺失或 Redis 执行错误抛出并保留 cause；原 take 不消费，恢复库存后用原请求继续，不重复扣次数 |
| 条件库存扣减存在分步窗口 | 原实现 GET→DECR，负数再 INCR；命令间可插入删除或进程中断 | 统一为单 Key Lua 检查存在性、判断余量及 DECR，售罄不写负数；两类库存复用该操作 |
| 回补重建丢失库存 | 真实 Redis 上两类 missing-key 回补断言均失败，INCR 创建新 Key | 原子确认 Key 存在后再回补；缺失明确报错且保持缺失，不能从数据库热路径回填 |
| 回补失败被吞掉 | 真实 Redis WRONGTYPE 下活动/奖品回补均未抛出 | 记录业务标识和堆栈后传播异常；SQL 与回补同时失败时保留原 SQL 异常并附加 suppressed |
| 订单 SQL 异常回滚后泄漏预扣库存 | 真实 MySQL trigger 令订单 INSERT 抛错；take 已回滚，奖品 Redis 期望 10、实际 9 | 参与/订单仓储观察 Spring afterCompletion，只有事务未开始或确认回滚才对异常回补；业务拒绝仍由原调用方回补，避免重复归还 |
| 同宿主机节点配置未生效 | 两个 Spring 实例配置 worker 2/7，原实现实际都使用 IP 推导的节点 3 | 显式读取 worker/datacenter 配置；验证实际 ID 节点位以及两实例共 2,000 个 ID 不重复 |
| 配置缓存在数据库提交前失效 | 三项 Spring 事务回归均复现：事务内已删除缓存，回滚也删除 | 有事务时 afterCommit 再失效；回滚保留旧缓存，无事务仍立即失效 |

主要文件：RedisUtil.java、StrategyRepository.java、ActivityRepository.java、StockReservationTransactions.java、UserTakeActivityRepository.java、OrderRepository.java、BaseActivityPartake.java、SnowflakeIdGenerator.java、TwoLevelCacheService.java、application.yml。

事务异常处理没有增加重试或降级。库存错误没有转换成正常业务结果。活动参与 ID 在预扣之前生成，避免 ID 生成失败时已经扣库存。已有请求幂等、Outbox、消息 CAS、BLOCKED、分片接口和数据库结构保持原契约。

活动名称筛选曾列为疑点，但真实 SQL 测试通过，源码条件完整；已撤回该疑点，未修改相应 Mapper。库存 Lua 的并发回归验证最终仅售出配置的 3 份库存；本轮没有用进程强杀实验证明或消除 Redis 与 MySQL 之间的崩溃窗口。

## 验证

运行 JDK：Amazon Corretto 1.8.0_482。使用既有独立 lottery-mq-e2e 测试栈：MySQL 13309、Redis 16379、Kafka 19092；另经标签与端口确认使用 lottery-final-sql-it（13319）运行 SQL 专项。没有启动或修改业务栈及性能栈。测试会重建专用 E2E fixture，不连接默认业务端口。

```bash
# 完整构建与默认单元测试。
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn clean package

# 在已确认的专用栈上执行原有及新增集成回归。
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn test \
  -Dtest=MqStateCasManualIT,DrawRequestOwnershipManualIT,FinalBusinessE2eManualIT,MqMiddlewareE2eManualIT,RedisStockFailureManualIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dlottery.it.mysql=jdbc:mysql://127.0.0.1:13319/ \
  -Dlottery.it.redis.port=16379
```

| 门禁 | 通过 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| 默认单元测试 | 45 | 0 / 0 / 0 |
| MqStateCasManualIT | 13 | 0 / 0 / 0 |
| DrawRequestOwnershipManualIT | 9 | 0 / 0 / 0 |
| FinalBusinessE2eManualIT | 17 | 0 / 0 / 0 |
| MqMiddlewareE2eManualIT | 9 | 0 / 0 / 0 |
| RedisStockFailureManualIT | 6 | 0 / 0 / 0 |
| 合计（不累计重复运行） | **99** | **0 / 0 / 0** |

新增真实 HTTP 场景验证：奖品 Key 缺失保留 take；take/order INSERT trigger 失败后的真实回滚与库存只归还一次；移除故障后原 requestId 恢复。事务单元测试额外验证开始失败、提交未知、回滚失败和 SQL/Redis 双重故障。缓存测试运行真实 Spring 事务生命周期，连接为 Mock；节点测试为同进程两个 Spring 配置实例，均不冒充真实多节点部署。

修复前定向运行记录：RedisStockFailureManualIT 6 项中 5 失败；新增 HTTP 两项均失败；SnowflakeNodeConfigurationTest 1 项失败；CacheTransactionInvalidationTest 3 项均失败。修复后保留原断言通过。结果摘要从 Surefire XML 提取，见 [failure-review-20261024.json](validation/failure-review-20261024.json)。默认构建日志位于本机 `/tmp/lottery-review-package.log`，集成日志位于 `/tmp/lottery-review-integration.log`，未复制含环境属性的完整 XML 到文档目录。

独立测试栈首次启动时 Kafka 因 ZooKeeper 旧会话的临时节点冲突退出；确认日志后待旧会话过期，重新启动原测试 Broker，健康检查与后续 Broker 停机恢复用例均通过。未为此增加业务重试或更改测试断言。

验证结束后已停止本轮启动的 E2E 四项服务及 SQL 专项容器，未删除容器或数据卷；`docker ps` 确认没有遗留运行容器。`git diff --check` 与本轮调整文档的本地链接检查通过。

## 部署配置

application.yml 新增 `lottery.ids.worker-id=${LOTTERY_WORKER_ID:0}` 与 `lottery.ids.datacenter-id=${LOTTERY_DATACENTER_ID:1}`。默认 0/1 仅供本地单实例；同一数据中心的多个实例必须分配不同的 0..31 worker-id。多机共享默认值仍会冲突，本次没有实现注册中心自动分配。没有新增数据库迁移。

## 剩余 failure case 与优化顺序

1. **优先：预扣后的崩溃与自动对账。** Redis 预扣成功后、take/order 落库前 JVM 崩溃，仍可能留下无法自动归还的预留；COMMIT/ROLLBACK 结果未知、Redis 回补不可用也保留待核查义务。本次修复处理可观察到的本地异常，不提供持久预留账本或跨存储事务。下一步需要设计按 take/request 关联的持久预留事实与恢复协议，并用真实强杀与提交断连验证，不能简单 catch 后 INCR 或从滞后 MySQL 回填。
2. **优先：对账运维闭环。** BLOCKED、运行期缺失库存和本轮新增事务结果未知日志仍需人工发现、核实和处理。可先补按业务标识查询的只读对账命令及告警，再设计经事实校验的单订单恢复；不能定时把所有异常状态改回 INIT 或给库存加一。
3. **接入真实发奖时：外部副作用幂等。** 当前只有本地模拟渠道。需要定义渠道 orderId 幂等键、成功但本地未记账时的查询/恢复契约，并验证重复通知、超时及结果未知。当前本地 CAS 不证明外部恰好一次。
4. **部署扩展时：真实调度、多节点与消息高可用。** XXL-Job Admin 外部调度与分工、多副本 Kafka 切换未验证；本地仍为单 Broker/单副本/acks=1。显式节点配置解决可配置性，同一节点号重复配置仍由部署负责。MQ processing 锁也不能当作外部发奖恰好一次证明。
5. **有实时变更需求再做：跨实例缓存一致性。** afterCommit 解决过早失效，但其它实例 L1 和已在途的旧版本加载仍按 TTL 最终一致；需要即时生效时再定义版本或广播契约。不能宣称本次已实现强一致缓存。

另有明确未实现功能：ActivityDeployImpl.updateActivity 仍是空 TODO，没有当前 HTTP 入口；应在定义活动可编辑状态、库存和策略修改规则后独立实现，当前不能宣称活动更新可用。不存在活动的抽奖目前会由 HTTP 统一返回系统错误，业务码细分也可作为后续接口体验优化。本轮没有扩大到新的管理 API、真实渠道或依赖升级。

性能方面，库存主路径从 GET+DECR 至少两次 Redis 命令往返合并为一次脚本调用；没有重跑吞吐基准，不能沿用旧 150 RPS 数字证明新版本的性能提升。建议在上述一致性和对账问题收口后，再按既有独立性能环境做相同负载的对照实验。
