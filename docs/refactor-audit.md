# Refactor Audit — 以真实代码为唯一事实依据

> 审计基线 commit：`a84ea24 feat: strengthen draw guard and config cache`
> 本文档所有结论均来自对当前源码、mapper XML、SQL 初始化脚本的逐文件阅读，
> 类名/方法名/表名如有出入以本文所列的真实代码为准。

---

## 1. 当前模块关系

Maven modules（根 pom `Lottery`，Spring Boot 2.3.5.RELEASE，Java 8）：

```
lottery-common        Result / PageRequest / Constants（枚举 + RedisKey）
lottery-rpc           纯 Dubbo 接口 + DTO（依赖仅 common）
lottery-domain        业务规则（activity/strategy/award/rule/support.*）
lottery-application   use case 编排 + Kafka producer/consumer/dispatcher + XXL-Job
lottery-infrastructure MyBatis dao/po/mapper XML、Repository 实现、RedisUtil、Guards、TwoLevelCacheService
lottery-interfaces    SpringBoot 启动模块（war）：Dubbo facade、MapStruct assembler、全部测试
```

真实依赖图：

```
                 ┌────────────┐
                 │ lottery-rpc│ ──(Dubbo 接口+DTO)──┐
                 └────────────┘                    ▼
┌──────────┐   ┌───────────────────────┐   ┌───────────────────────┐
│ lottery- ├──▶│ lottery-application   ├──▶│ lottery-domain         │
│interfaces│   │ (MQ/Jobs/use case)    │   │ (+common)              │
│ (boot app)│  └───────────────────────┘   └───────────▲──────────┘
│          │   ┌───────────────────────┐               │
│          ├──▶│ lottery-infrastructure├───────────────┘
│          │   │ (MyBatis/Redis/Guards)│
└──────────┘   └───────────────────────┘
dubbo + nacos 依赖只存在于 interfaces 模块
```

分层越界（重构对象）：

| 问题 | 位置 |
| --- | --- |
| domain 直接依赖 db-router 中间件（`IDBRouterStrategy.doRouter/clear/setDBKey`） | `ActivityPartakeImpl.java:68`、`AwardOrderImpl.java:59` |
| domain 直接使用 Spring `TransactionTemplate` | 同上两个类 |
| Redis 配置类放在 domain | `domain/support/redis/RedisConfig.java`（其中注入的 `RedisTemplate<String,Object>` 除自身外无人使用——实际全部走 `RedisUtil` 的 `StringRedisTemplate`），且 `@EnableCaching` 下没有任何 `@Cacheable` |
| 锁接口（domain/support/lock/*）由 infrastructure 实现，方向正确但 guard 内嵌太多业务语义（见 §3） | `infrastructure/lock/*` |

自研分库分表组件：本地目录 `../db-router-spring-boot-starter`（groupId `cn.bugstack.middleware`，即任务所述 dbRouterForGem 的本地形态），路由算法为
`(size - 1) & (hash ^ hash >>> 16)`，`size = dbCount * tbCount = 8`。
已知限制详见 `docs/architecture.md` 第 6 节（重构完成后输出）。

---

## 2. 当前完整抽奖链路（真实 class/method/table/key/topic/事务边界）

### 2.1 入口

唯一的对外入口是 **Dubbo**（`@org.apache.dubbo.config.annotation.Service`）：

* `LotteryActivityBooth`（interfaces/facade）implements `ILotteryActivityBooth`
  * `doDraw(DrawReq)` → 正常抽奖
  * `doQuantificationDraw(QuantificationDrawReq)` → 规则树决策出 activityId 再抽奖
  * `queryRunningActivityList()` / `queryRuleTreeList()` / `queryRuleCriteria()`
* `LotteryActivityDeploy` implements `ILotteryActivityDeploy` → ERP 分页查活动配置
* **项目内不存在任何 HTTP Controller**（jmeter 脚本目录中的 jmx 也无法对接现有入口）

### 2.2 doDrawProcess 编排（application/process/draw/impl/ActivityProcessImpl.java）

```text
① activityDrawProcessGuard.tryClaim(activityId,uId)
     Redis SET lottery_draw_inflight_{act}_{uId} = "1" NX PX 3000ms
     （value 固定 "1"；release 直接 DEL —— 存在误删他人锁的 owner 缺陷）
② activityPartake.doPartake(PartakeReq)            [domain/activity/partake]
③ if partake SUCCESS && mq.enabled:
     kafkaProducer.sendLotteryActivityPartakeRecord(record)      ★待删除链路
       topic=lottery_activity_partake key=takeId value={stockSurplusCount 绝对值}
④ drawExec.doDrawExec(DrawReq{uId,strategyId,takeId})
⑤ FAIL → activityPartake.consumeTakeActivity(uId,act,takeId)
       user_take_activity state 0→1 CAS（UPDATE ... AND state = 0）
⑥ placeOrder(AwardOrderReq)                        [domain/award/order]
⑦ order 失败 → drawExec.recoverDrawAwardStock(strategyId,awardId)
     Redis INCR prize stock key 回补
finally guard.release（DEL）
```

### 2.3 参与（BaseActivityPartake 模板方法 + ActivityPartakeImpl）

| 步骤 | 实现 | 数据面 |
| --- | --- | --- |
| 未消费 take 快路径 | `queryNoConsumedTakeActivityOrderFromCache` | Redis `lottery_user_take_no_consumed_{act}_{uId}`（JSON，TTL1800s）；miss 时回源 DB `SELECT ... WHERE u_id=? AND activity_id=? AND state=0 ORDER BY id DESC LIMIT 1` |
| 活动/资格检查 | `checkActivityBill` | `state==DOING(5)`、时间窗、`stockSurplusCount>0`（基于活动缓存的 DB 值，可能滞后） |
| 活动库存预扣 | `substractionActivityStockByRedis` | Redis `DECR lottery_activity_stock_count_{act}`；**miss 时 `initActivityStockIfAbsent` 从 MySQL activity.stock_surplus_count SETNX 回填**（活动 RUNNING 中回填滞后值 ⇒ 库存复活风险）|
| 落库 | `grabActivity` | 手动 `dbRouter.doRouter(uId)` + `TransactionTemplate`：<br>1) `user_take_activity_count` INSERT..ON DUPLICATE KEY UPDATE left_count-1（UNIQUE(u_id,activity_id) 兜底）<br>2) INSERT `user_take_activity`（UNIQUE(take_id)；无 requestId 幂等列）<br>DuplicateKeyException → INDEX_DUP → 回补 Redis INCR |
| 补库存回滚 | `recoverActivityStockByRedis` | Redis INCR（try/catch 吞错，日志 warn/error）|

### 2.4 抽奖（AbstractDrawBase → DrawExecImpl）

| 步骤 | 实现 | 说明 |
| --- | --- | --- |
| 读策略聚合 | `strategyRepository.queryStrategyRich` | TwoLevelCache：Caffeine L1(TTL30s, max10000) + Redis L2(TTL600s+jitter120s+null 占位60s)，key `lottery_cache_strategy_rich_{sid}` |
| 初始化概率 | `checkAndInitRateData` 按 strategyMode 从 `DrawConfig.drawAlgorithmGroup`（static map）选算法；进程内存态 `rateTupleMap/awardRateInfoMap`，synchronized 双检 |
| 算法 A | `SingleRateRandomDrawAlgorithm`：`awardRate*100 -> int`（百分级截断，0.1%→0 丢失）+ Fibonacci 散列 128 slot，`SecureRandom.nextInt(100)` |
| 算法 B | `EntiretyRateRandomDrawAlgorithm`：对未售罄奖品做 `divide(totalRate)` 动态归一化后 CDF（售罄后其它奖品中奖率被抬高，改变原配置语义）|
| 不获奖名单 | `queryNoStockStrategyAwardList` | Redis Set `lottery_cache_strategy_no_stock_award_{sid}`，TTL60s |
| 奖品库存预扣 | `deductStockByRedis` | `DECR lottery_strategy_award_stock_count_{sid}_{awardId}`；sold out 时 sAdd 标记并直接返回 soldOut；miss 时同样从 MySQL `strategy_detail.award_surplus_count` SETNX 回填（结算滞后 ⇒ 回填值偏大）|
| 查重摇上限 | DrawExecImpl 循环 maxRetry= max(3, N*2) 次“重摇其他奖品”|
| 奖品信息 | `queryAwardInfoById` per award（两段缓存 lotter_cache_award_*）|

### 2.5 中奖订单 AwardOrderImpl.placeOrder

手动 `dbRouter.doRouter(uId)` + `TransactionTemplate` 单一本地事务：

```text
UPDATE user_take_activity SET state=1 WHERE u_id=? AND activity_id=? AND take_id=? AND state=0
INSERT user_strategy_export_{xxx}
  order_id=snowflake, uuid=String(order_id), UNIQUE(idx_uuid)=幂等防重,
  grant_state=0(INIT), mq_state=0, stock_mq_state=0
DuplicateKeyException → INDEX_DUP
```

`scanAwardStockMqState` SQL：`WHERE stock_mq_state IN (0,2) ORDER BY create_time LIMIT ?`，
索引 `idx_stock_mq_state_create_time(stock_mq_state,create_time)` 已存在于 `20260315_async_award_stock_outbox.sql` ✓。
`scanInvoiceMqState`：`WHERE stock_mq_state=1 AND (mq_state=2 OR (mq_state=0 AND create_time<NOW()-30min))` **无 LIMIT** 且只有 `(mq_state,create_time)` 索引可用（条件包含 stock_mq_state=1 但该索引仍能命中 mq_state 前缀，可接受；补 LIMIT 是低成本改进）。

### 2.6 异步链路 A：奖库存结算（Outbox）

```text
AwardOrder 创建(stock_mq_state=0)   ← 同一本地事务（Outbox 写入点 ✓）
AwardStockSettlementOutboxDispatcher  @Scheduled(fixedDelay 5s) ← 生产路径唯一 @Scheduled
  遍历 db01/db02 × tb000..003：
    scanAwardStockMqState(limit=10)
    isStockMqCompleted(COMPLETE) → skip + clearDispatchMarker
    tryClaimDispatch(Redis lottery_strategy_award_stock_mq_dispatching_{orderId} 60s)
    kafkaProducer.sendStrategyAwardStock(key=orderId)   topic=lottery_strategy_award_stock
    onSuccess: 仅日志（状态留 INIT 由 consumer 收口）
    onFailure: UPDATE stock_mq_state=2(FAIL) + clearDispatchMarker
LotteryStrategyAwardStockListener (@KafkaListener concurrency 4)
  1) redisGuard.isProcessed(DONE 86400s)? → ack skip
  2) awardOrder.isStockMqCompleted(stock_mq_state==1)? → ack skip
  3) tryClaimProcessing(PROCESSING 300s) else ack skip
  4) queryInvoiceByOrderId(u_id,uuid=orderId) null → markProcessed/DONE + ack skip
  5) strategyRepository.deductStock(strategy_detail -1 WHERE surplus>0)   ★裸调用，无账本
  6) updateStockMqState COMPLETE
  7) markProcessed(DONE) + clearDispatchMarker
  8) sendLotteryInvoice(key=orderId) async callback 更新 mq_state=1/2
```

**crash window（§十二 任务指出的故障真实存在）**：
第 5 步 DB 扣减成功后 JVM crash → stock_mq_state 仍是 0/PROCESSING → Kafka redelivery →
第 2 步判断 false → 再次执行第 5 步 → **同一 orderId 重复扣减 strategy_detail 库存**。
`deductStock` 只是 `-1 WHERE surplus>0` 的累加式更新，没有任何账本约束只能成功一次。

### 2.7 异步链路 B：发奖 invoice

```text
LotteryInvoiceListener (@KafkaListener concurrency 4)
  isAwardCompleted(grant_state==1) → ack skip   （DB 兜底判定 ✓）
  tryClaimProcessing(Redis invoice guard 300s) else → 再查一次 isAwardCompleted，否则 throw 触发重投
  distributionGoodsFactory.getDistributionGoodsService(awardType) → Map static 注册（GoodsConfig）
  doDistribution(GoodsReq{uId,orderId,awardId}) → CouponGoods/RedeemCodeGoods/PhysicalGoods/DescGoods
    全部为 logger.info("模拟...") + updateUserAwardState(grant_state=1, grant_date=now)
    UPDATE ... WHERE u_id=? AND order_id=? AND award_id=?   （重复执行结果不变，stub 层面幂等 ✓）
  Assert SUCCESS → releaseProcessing → ack
```

发奖由 OrderRepository.updateUserAwardState 落库。真实第三方接入时需要 orderId 作为 idempotencyKey——目前 stub 未体现也未声明。

### 2.8 后台调度现状

* XXL-Job 已存在（`LotteryXxlJobConfig` + `LotteryXxlJob`）：
  * `lotteryActivityStateJobHandler`：扫 state in (4,5)，PASS→DOING(doing)、过期 DOING→CLOSE(close)（IStateHandler）
  * `lotteryOrderMQStateJobHandler`：参数指定 dbIdx，扫 invoice 补偿重发（mq_state）
* Spring `@Scheduled` 生产路径 1 处：`AwardStockSettlementOutboxDispatcher.dispatchPending()`
  （此外 `LotteryApplication` 上挂着无意义的 `@EnableScheduling`）

### 2.9 表/键/主题一览（真实命名）

表：`activity`、`award`、`rule_tree(+node/_line)`、`strategy`、`strategy_detail`（全局库 lottery=db00，不分片）；
分片表（db01/db02 × _000.._003）：`user_take_activity`、`user_take_activity_count`、`user_strategy_export`。
Redis key 命名空间见 `Constants.RedisKey`（stock / cache / no-stock set / MQ guard DONE·PROCESSING·DISPATCHING / inflight）。
Topic：`lottery_activity_partake`、`lottery_strategy_award_stock`、`lottery_invoice`（KafkaTopicConfig 声明分区副本）。

---

## 3. 待删组件调用关系核查（生产 / 仅测试 / 完全未使用）

| # | 组件 | 生产路径 | 测试路径 | 结论 |
| --- | --- | --- | --- | --- |
| 1 | lottery-rpc module + ILotteryActivityBooth/ILotteryActivityDeploy + 全部 rpc dto | Booth/Deploy facade 实现它；无任何外部 consumer repo 引用本项目 jar（单体部署）| LotteryActivityBoothTest 通过 Spring 上下文调用 facade（不是 RPC 调用）| **删除**；业务逻辑迁 HTTP Controller |
| 2 | Dubbo `@Service` 注解 + dubbo-spring-boot-starter + nacos-discovery-starter + yml dubbo/nacos 配置 | LotteryApplication(`@EnableDubbo`)、两个 facade | — | **删除** |
| 3 | Rule Engine：EngineFilter/EngineBase/RuleEngineHandle/LogicFilter/UserAge/UserGender Filter/TreeRuleRich 等 | `doRuleQuantificationCrowd`（仅被 Booth.doQuantificationDraw 调用）；主链路不经过树 | RuleEngineTest | **整链删除**（含 rule_tree 三张表 SQL、IRuleTreeDao 系、rule-tree 两级缓存、RPC DTO）|
| 4 | StateConfig/AbstractState/IStateHandler/StateHandlerImpl/7 个 State 类 | 生产仅用到 `doing`(4→5)、`close`(5→7)（LotteryXxlJob）；其余 arraignment/checkPass/checkRefuse/checkRevoke/open 无生产 caller | ActivityTest | **收敛为 ActivityLifecycleService**（ALTER TABLE 已是 CAS 语义）；删 7 个类 + Map 注册 |
| 5 | ShortCode/RandomNumeric/IdContext/Constants.Ids | 无生产引用（生产只用 SnowFlake，经 Map 注入）| SupportTest、UserStrategyExportDaoTest 演示 | **删除**；保留 Snowflake 直注 |
| 6 | Activity Partake Stock Kafka（producer/Listener/updateActivityStock/topic/config） | ActivityProcessImpl② → Listener → IActivityPartake.updateActivityStock → Activity_Mapper.updateActivityStock（绝对值覆盖，带单调递减保护但有偏移/丢消息问题）| KafkaProducerTest 发送 | **整链删除** |
| 7 | SingleRateRandomDrawAlgorithm | DrawConfig 注册 SINGLE 模式（seed 数据全部 strategy_mode=2 即 ENTIRETY，运行时默认走 Entirety）| 无专属测试 | **删除**（合并进 WeightedRandomSelector）|
| 8 | EntiretyRateRandomDrawAlgorithm | 主力算法 | 无专属测试 | **替换**为 WeightedRandomSelector（快照整数权重 + 二分，不做归一化）|
| 9 | DrawConfig algorithm map | AbstractDrawBase 使用 | — | **删除** |
| 10 | GoodsConfig/DistributionGoodsFactory/DistributionBase/IDistributionGoods | InvoiceListener 发奖 | — | **扁平化**为 AwardFulfillmentHandler + Spring List 注入 registry（保留 4 个 stub 逻辑，README 声明 adapter stub）|
| 11 | Spring @Scheduled | dispatcher 一处 | — | **迁移**到 XXL-Job 分片 job；删 @EnableScheduling |
| 12 | TwoLevelCacheService 四类缓存 | activity/strategyRich/award/ruleTree 全覆盖 | TwoLevelCacheServiceTest | **收缩**：Caffeine 只保留 StrategySnapshot；activity 改单级 Redis；删除 ruleTree 缓存 |
| 13 | Redis MQ Guard DONE/PROCESSING/DISPATCHING | 见 2.6/2.7 | RedisActivityDrawProcessTest（inflight）| PROCESSING 保留为并发优化、DISPATCHING 保留为投递去重、**DONE 从事实来源降级删除**（DB 唯一账本接管最终幂等）|
| 14 | substractionActivityStock(PartakeReq)（DB 直减活动库存非 Redis 版本） | BaseActivityPartake 定义 abstract、Impl 有实现、**无任何 caller** | — | **完全未使用，删除** |
| 15 | RedisConfig(RedisTemplate<String,Object>) | 注入而从未真正使用（全走 stringRedisTemplate）| — | **完全未使用，删除**（@EnableCaching 无 @Cacheable 一并去掉）|

---

## 4. 审计发现的正确性问题（重构必须修复的根因清单）

1. **奖库存结算 crash window 双扣**（见 2.6）→ 新增 `award_stock_settlement` 账本，
   与 `strategy_detail` 同库（db00，非分片）可放同一本地事务；
   `INSERT settlement(order_id UNIQUE)` duplicate → 视为已结算短路；
   **同一 orderId 只可能扣一次数据库库存**。
2. **draw inflight 锁 value 固定 "1"、DEL 释放**：A 超时后 B 获锁，A finally DEL 会误删 B 的锁 →
   改为 token=UUID NX PX + Lua compare-and-delete。
3. **请求幂等缺失**：只有“并发防重 + 未消费 take 复用”，网络超时后的重试会消耗新的参与次数（新 take）→
   API 增加 `requestId`；`user_take_activity` 加 `request_id`（UNIQUE）、`user_strategy_export` 加 `take_id`
   关联列，重试时按 requestId 回放 take/order 结果；DB UNIQUE 为最终兜底。
4. **活动库存 Redis miss 回填 DB 滞后值**（RUNNING 期间库存复活风险）→ fail closed：
   返回“系统繁忙/库存状态异常”+ error 日志，交给预热/对账机制恢复。
   奖品库存 miss 同样不再静默回填，视同不可售并记录错误。
5. **活动库存 Partake MQ 异步绝对值覆盖 MySQL**：消息按 takeId 分区、丢消息即永久偏差；
   并且 MySQL 与 Redis 双向漂移靠 `WHERE surplus > #{v}` 保护——链路本身无存在必要 → 整链删除；
   活动启动（READY→RUNNING CAS 成功后）SETNX 预热活动库存与各奖品库存；
   活动关闭时一次性把 Redis 剩余量持久化（普通 UPDATE，不开新 MQ）。
6. **Entirety 归一化 / Single 截断 + 斐波那契槽位**都破坏概率语义 → WeightedRandomSelector：
   snapshot 构建期 BigDecimal×1,000,000 取整为权重，剩余概率显式 NO_PRIZE；
   命中售罄奖品视为 NO_PRIZE（不重摇、不改概率）。同时删除 no-stock Set 结构。
7. **scanInvoiceMqState 无 LIMIT** → 加 LIMIT，防止补偿任务一次拖全表。
8. **outbox dispatcher onSuccess 不落 SENT 状态**：INIT 记录在 consumer 完成前每 5s 被重复扫描+投递
   （PROCESSING/DISPATCHING guard 掩盖成本）→ 状态语义统一为
   `INIT →(dispatch 成功) SENT →(消费完成) DONE / 失败 FAILED`，dispatcher 只扫 INIT/FAILED 并带 batch limit。

## 5. 与任务书预期不一致之处及处理建议（不要机械照改的部分）

| 任务书假设 | 实际代码 | 处理 |
| --- | --- | --- |
| “HTTP 入口存在” | 只有 Dubbo facade，无 Controller | R3 中新建 REST Controller 承接（保持参数校验薄层）|
| “如果存在 Redis MQ PROCESSING/DONE/DISPATCHING Guard 则简化” | 三个 Guard 都真实存在且承担不同职责 | 仅删除 DONE（DB 账本接管）；PROCESSING/DISPATCHING 保留为性能/并发控制 |
| “outbox 查询需补 (stock_mq_state,create_time) 联合索引” | 索引已存在 ✓ | 只修 invoice scan LIMIT + 状态语义 |
| “idGenerator 多实现只被演示用” | 属实（ShortCode/RandomNumeric 仅测试 demo）| 删除并同步改写对应测试 |
| “状态机是审批流” | alterState SQL 本身已是 CAS；但 8 态中仅 4/5/7 有生产含义 | 状态收敛为 DRAFT(1)/READY(4)/RUNNING(5)/CLOSED(7)，枚举删除 2/3/6/8；migration 提供历史数据归并注释语句（默认不执行 DROP/UPDATE）|
| “award stock settlement ledger 建 awards 分片库” | strategy_detail 在全局库 db00，不在分片 | ledger 建在 db00（与扣减表同库），才能用一个本地事务保证 insert+update 原子性 |
| “XXL-Job 不存在/部分存在” | 已有两类 job | 新增 OutboxDispatchJob（吸收 @Scheduled dispatcher）、MesageCompensationJob 参数沿用分片约定 |

## 6. Baseline 构建验证

* `mvn clean compile -DskipTests` ✅ 通过（JDK 17/25 均编译通过，source/target 8）
* 现有测试全部依赖本机 MySQL/Redis/Kafka/XXL-Job 环境（SpringRunnerTest 家族），不适合作为 CI 门禁；
  本次重构将：纯单元测试落地 selector/guard/idempotency/snapshot；SpringRunnerTest 家族更名 `*ManualIT`
  并从 surefire 默认排除，附手工运行说明。
