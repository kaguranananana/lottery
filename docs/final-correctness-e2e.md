# Lottery 最终正确性修复与真实 E2E

2026-10-12，基于主仓库 `2527478` 的实际工作区完成。任务开始时 Lottery 无未提交修改；关联 dbRouterForGem 工作区有既有修改，全部保留，未纳入本次提交。本轮未修改生产配置或业务数据库。

## 已确认的问题与修复

| 问题 | 根因与修复前证据 | 实际修复 |
| --- | --- | --- |
| 活动创建不完整 | ActivityVO 无策略号，INSERT 缺 strategy_id、stock_surplus_count；真实 MySQL 查询策略号为 NULL | 创建聚合绑定策略及明细；活动剩余库存从总库存初始化，明细剩余库存从奖品总量初始化；Mapper 显式插入两个活动字段；原创建事务不变 |
| 活动第二页漏扫 | 按 id 翻页却未 SELECT id，真实查询游标为 NULL | SELECT 返回 id；23 条活动验证游标严格递增、不重复且全部启动，两轮 Handler 不重置库存 |
| 库存分歧无限重试 | 条件扣减失败回滚账本后写 FAIL，而 Outbox 扫 FAIL；原 HEAD 真实用例期望 4、实际 2 | 增加库存专用 BLOCKED=4 等待对账。发送 FAIL 仍重试；BLOCKED 不受发送回调影响，修复数据后显式释放原 Outbox |
| 活动启动不可恢复 | 未检查开始时间，先 DOING 后预热；预热异常吞掉 | 全局库活动行锁内检查 begin <= now < end，完整 SETNX 预热后才 CAS DOING；异常抛出并回滚状态至 PASS，下轮补齐缺失 Key；运行中禁止自动回填 |

创建、分页、提前启动三项回归在修复前运行 3 项、失败 3 项。另从 `git archive 2527478` 建立临时源码快照，只拷入新的库存分歧回归，运行 1 项、失败 1 项，精确断言为 `expected 4 but was 2`。旧 CAS 用例按新契约将库存分歧期望从 FAIL 更新为 BLOCKED，并扩大到五状态矩阵；没有降低断言标准或重试测试消除失败。

主要实现文件：ActivityVO.java、ActivityDeployImpl.java、ActivityRepository.java、IActivityDao.java、Activity_Mapper.xml、LotteryXxlJob.java、Constants.java、OrderRepository.java、LotteryStrategyAwardStockListener.java、UserStrategyExport_Mapper.xml。接口注释、README、architecture.md 和 Trellis 规范同步更新。新增 FinalBusinessE2eManualIT.java；扩展 MqMiddlewareE2eManualIT.java、MqStateCasManualIT.java 与 MqCallbackStateTest.java。

## 库存恢复、CAS 与 ACK 的取舍

继续复用全局事务中的 `UNIQUE(order_id)` 账本与 `award_surplus_count > 0` 条件扣减，无新表、跨库事务或重试框架。

| 事件 | 行为 |
| --- | --- |
| Kafka 发送失败 | INIT→FAIL；下一轮 Outbox 重投，超时 SENT 仍可重投 |
| 数据库异常 | 原异常和堆栈向上传播，消费者不 ACK；原订单仍保留可补偿库存状态 |
| 条件扣减不满足 | 账本插入回滚；独立 CAS 将 INIT/FAIL/SENT→BLOCKED，落库成功后 ACK；不发送奖品消息 |
| BLOCKED CAS 零更新 | 回读订单，仅 COMPLETE/BLOCKED 可正常返回；订单缺失或其它状态抛出，禁止盲目 ACK |
| 迟到发送成功或失败 | 不修改 COMPLETE/BLOCKED，也不刷新其更新时间 |
| 并发或重放已有结算事实 | 成功结算可从包括 BLOCKED 在内的未完成状态进入 COMPLETE；账本保证不重复扣减 |

ACK 的依据是“库存义务已结算”或“原订单已持久保留为待对账”，而非把库存分歧解释为结算成功。BLOCKED 不被自动扫描，也不进入发奖扫描；它保留完整原订单及待完成义务。真实 Kafka 重复消息的位点已提交，同时验证无账本、无发奖；人工修复并释放后同订单经 Outbox/Kafka 完成，只有一条账本及一次扣减。

对账恢复须先核对正确用户分片订单、全局结算账本、策略奖品库存和在途预扣事实，修复真实分歧。不要简单给库存加一，或把全部历史 FAIL 批量改为 BLOCKED。确认后在该订单的物理分片执行带 `u_id、order_id、stock_mq_state=4` 条件的单订单 `4→0` 更新，再调用原补偿 Handler。已有账本时按已结算事实恢复，禁止再次增加库存。该过程目前是明确的人工 runbook，未新增管理接口或自动对账程序。

预热恢复限于从未运行的 PASS 活动。活动行锁使并发启动仅一个成功；Redis 部分写入不回滚，但下轮 SETNX 保留旧值并补齐。DOING/CLOSE 的直接预热抛出、重复启动返回 false，运行期 Key 丢失须独立对账。Redis 操作期间持有活动行锁属于低频启动路径；Redis 超时会延长锁占用，故障任务失败后由下一轮调度恢复。

## 实际执行环境与命令

Java 为 Amazon Corretto `1.8.0_482`，Maven `3.9.14`，Spring Boot `2.3.5.RELEASE`。Surefire XML 实际记录 java.version=1.8.0_482，编译目标和运行 JDK 均为 Java 8。

| 用途 | 环境 |
| --- | --- |
| E2E | MySQL 8.0.46（13309）、Redis 7.2.16（16379）、Kafka 2.8.1（19092）和 ZooKeeper 3.8；独立 lottery-mq-e2e Compose |
| SQL 专项 | 本轮新建 lottery-final-sql-it，MySQL 8.0.46，仅绑定 127.0.0.1:13319，独立匿名卷和空测试账号配置 |
| HTTP | Spring Boot RANDOM_PORT，真实网络请求与生产 Controller |
| 路由组件 | 相邻仓库当前工作区编译安装的 db-router-spring-boot-starter:1.0.4；既有修改保留，无源码改动或提交 |

DROP/CREATE 前核实 E2E MySQL 的 Docker 项目、服务标签及 13309 端口；无业务卷挂载。E2E 重建专用 lottery/lottery_01/lottery_02 fixture；SQL 专项只连接新建空容器。生产基线仅加载 CREATE TABLE，真实执行库存 Outbox、V2 账本迁移；HTTP E2E 还通过 mysql 客户端执行完整 V3 两库/八表迁移。仅专用测试 Redis/Kafka 被停止和恢复。

从主仓库根目录执行：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
export PATH="$JAVA_HOME/bin:$PATH"

# 构建当前路由工作区；其 POM 固定跳过自身测试，未计入通过数量。
mvn -f ../db-router-spring-boot-starter/pom.xml install -DskipTests=false

# E2E 专用栈，测试内会检查 MySQL 身份。
docker compose -f lottery-interfaces/src/test/resources/mq-e2e/docker-compose.yml up -d --wait

# SQL 专用空实例；同名容器已存在时使用本轮创建的实例，不重建其它实例。
docker run -d --name lottery-final-sql-it --label lottery.purpose=final-sql-it \
  -p 127.0.0.1:13319:3306 -e MYSQL_ALLOW_EMPTY_PASSWORD=yes mysql:8.0
docker exec lottery-final-sql-it mysqladmin ping --silent

mvn clean package
mvn test \
  -Dtest=MqStateCasManualIT,DrawRequestOwnershipManualIT,FinalBusinessE2eManualIT,MqMiddlewareE2eManualIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dlottery.it.mysql=jdbc:mysql://127.0.0.1:13319/
```

另实际执行过 `mvn test`、两个 SQL-only ManualIT 定向命令、FinalBusinessE2eManualIT 定向命令及两组 E2E 合跑。`surefire.failIfNoSpecifiedTests=false` 仅允许 reactor 中无匹配测试的模块通过，不忽略任何匹配测试失败。

## 最终结果与覆盖

| 门禁 | 通过 | 失败/错误/跳过 |
| --- | ---: | ---: |
| 默认单元测试，含 ACK 持久化异常回归 | 34 | 0/0/0 |
| MqStateCasManualIT，真实 SQL 状态矩阵/并发/超时扫描 | 13 | 0/0/0 |
| DrawRequestOwnershipManualIT，真实分片事务/约束/请求归属 | 9 | 0/0/0 |
| FinalBusinessE2eManualIT | 14 | 0/0/0 |
| MqMiddlewareE2eManualIT | 9 | 0/0/0 |
| 合计（不重复累计多轮执行） | **79** | **0/0/0** |

Java 8 全模块 clean package 成功，产物为 Lottery.war；无单独 lint 配置，`git diff --check` 通过。45 项集成用例在同一最终 Maven 命令中全部通过，随后对“不预填策略明细关联/库存”的加强断言再次定向验证 HTTP E2E。79 项包括单元与 SQL 专项中的 Mock；其中 **23 项 E2E 的 MySQL、Redis、Kafka、DAO、生产者及消费者均真实运行，无中间件 Mock**。

HTTP E2E 覆盖：配置创建、策略关联与初始库存、创建事务中后续唯一冲突导致前序写入回滚、23 条分页扫描、未来活动、到期关闭、6 个并发启动者、Redis 停机和 ACL 限制第二个 Key 写入失败、部分 Key 补齐与运行期缺 Key 不复活；八张订单表上真实 HTTP 抽奖/次数扣减/订单/Outbox/结算/模拟发奖；同 requestId 回放、A 未完成时 B 拒绝并恢复 A、12 个 HTTP 请求竞争用户 3 次额度、活动库存耗尽、库存分歧落库/ACK/对账恢复。

MQ E2E 覆盖：八张分片订单表、真实重复投递、消费者先于发送通知完成、迟到成功/显式注入迟到失败、Redis marker 真实过期和多轮乱序通知、SENT 超时补偿、发奖 FAIL/SENT/历史 COMPLETE 恢复、真实 Broker 停机发送失败及恢复、库存分歧停止重投后释放恢复。每条已接受 MQ 用例消息通过真实元数据检查消费提交位点；账本唯一及库存扣减不重复均有断言。

证据索引保留在 [final-correctness-results.json](validation/final-correctness-results.json)：从 Surefire 实际 XML 提取套件数量与每项测试名，排除环境属性、凭据和完整业务日志；四项修复前失败也分别列出。测试本身保留中文故障说明和生产错误堆栈，不静默忽略异常。

## 真实边界与剩余风险

- XXL-Job executor 真正初始化，但 Handler 由测试直接调用，未验证外部 Admin 的调度、鉴权和多节点分工配置。奖品渠道仍是项目已有本地模拟；真实第三方发奖与外部副作用幂等未验证。
- SQL 异常用例在真实 MySQL 上临时改名账本表，直接调用生产消费者并用 ACK 回调记录断言；恢复后的完成链路走真实 Kafka。不能描述为通过 Kafka 网络注入了数据库宕机。迟到失败通知也是明确的测试注入；真实发送失败由 Broker 停机独立验证。
- Kafka 为单 Broker、单副本、acks=1；未验证多副本主从切换、网络分区或生产 SLA。12 请求/6 启动者是正确性回归规模，不是吞吐或延迟基准。
- Redis 预扣与数据库提交间没有分布式事务。JVM 在预扣后、持久化前崩溃可能遗留库存预留，本轮没有消除或证明该崩溃窗口；运行期库存丢失不能按滞后数据库直接回填。共享策略库存或历史 DOING 且缺 Key 的活动须按在途订单独立对账。
- BLOCKED 待对账必须被运营发现并处理；目前提供持久状态、错误日志和人工释放流程，未提供自动告警接入或管理界面。旧 FAIL 历史数据下一次消费可重新分类，不能无证据批量停止重试。
- 发布须部署完整状态契约，禁止把新旧语义混跑当作已经验证。目标业务库需核对 Outbox、V2、V3 迁移和既有 NULL 活动配置；本轮未在生产修补历史数据。测试迁移成功不证明生产已经迁移。
- 相邻路由仓库本身有未提交修改，本轮验证基于本地制品；主仓库推送不代表那些组件修改已经推送到 dbRouterForGem。复杂 JOIN/子查询路由和多实例 Snowflake 配置仍是已有边界。

## 可用于简历的证据

可写：基于 Redis 库存预扣与 MySQL 条件更新实现库存控制；通过用户分片事务与请求唯一约束保持参与/订单归属及同请求回放；通过 Outbox、Kafka、唯一结算账本与消息 CAS 处理重复投递、迟到回调和停机补偿；区分可重试投递失败与需对账业务分歧；构建 23 项真实中间件 E2E、22 项真实 MySQL 集成及 34 项单元测试，覆盖两库八张订单表和完整 HTTP 业务链路。

不能宣称：生产 QPS、P99、性能提升比例、生产零丢失、跨 Redis/MySQL 的恰好一次、外部奖品发放恰好一次、Kafka 高可用或自动对账闭环。本轮证明的是列明故障场景下的可复现正确性。
