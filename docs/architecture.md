# Architecture Notes

本文记录重构后的架构决策、每个中间件的存在理由与已知约束。抽奖请求级失败语义见 README「Failure Cases」；逐类审计结论见 `docs/refactor-audit.md`。

---

## 1. 架构总览

```text
              HTTP
               │
        LotteryController          (interfaces —— 参数校验/DTO 转换)
               │
        ActivityProcessImpl        (application —— use case 编排 / MQ 触发)
     ┌─────┴──────────┐
ActivityPartakeImpl   DrawExecutor ── AwardOrderImpl   (domain —— 业务规则)
               │
       Repository 接口（domain 定义）
               │
  ActivityRepository / StrategyRepository / UserTakeActivityRepository /
  OrderRepository                       (infrastructure —— MyBatis+Redis+路由+事务)
```

分层职责：

* **interfaces**：HTTP adapter。不做业务规则。
* **application**：use case 编排（draw 幂等回放、Kafka 收发、XXL-Job 任务）。
* **domain**：参与资格检查、状态机转换矩阵、加权随机抽样、订单创建语义、发奖抽象。
* **infrastructure**：MyBatis、Redis（库存/锁/缓存）、dbRouter 路由细节、本地事务边界。

依赖方向治理成果：`cn.bugstack.*`（dbRouterForGem）只出现在 `lottery-infrastructure`；
`TransactionTemplate` 与手动 `doRouter/clear` 的模板代码全部下沉 repository。

## 2. 组件存在理由（自检清单）

### Redis 为什么存在？
高并发库存原子扣减（活动 + 奖品）、抽奖并发防重入锁、StrategySnapshot L2、活动配置缓存。
Redis 不承担任何“最终是否处理过”的事实判定：DONE 类 guard 已删除，幂等事实在 MySQL 唯一约束里。

### Kafka 为什么存在？
把数据库库存结算与发奖这两个慢操作从同步抽奖路径剥离，同时获得削峰与 at-least-once 重试能力。
消息 key 固定为 orderId，保证同一订单结算事件有序。

### Outbox 为什么存在？
避免 “DB 提交成功 → JVM crash → 消息永远没有发出去”。订单写入与 `stock_mq_state=0`
在同一事务落库，之后由 XXL-Job 扫描 `IN(0,2)` 补偿投递。

### settlement ledger 为什么存在？
保证 Kafka duplicate delivery / consumer crash 后的重复消费不会让同一个 orderId 扣两次库存。
`INSERT award_stock_settlement(UNIQUE order_id) + UPDATE strategy_detail ... surplus>0`
是同一个本地事务（两表同在全局库 db00），duplicate-key 即已结算。

### XXL-Job 为什么存在？
活动状态推进、Outbox 扫描、发奖补偿都需要可靠且有分片能力的调度，
而不是依赖每个应用实例自己的 `@Scheduled`（多实例重复扫全量分库）。调度点：

| Job | 参数 | 职责 |
| --- | --- | --- |
| lotteryActivityStateJobHandler | — | READY→RUNNING（CAS 成功后 SETNX 预热库存）；RUNNING 过期→CLOSED |
| awardStockSettlementOutboxJobHandler | `"1"` 或 `"1,2"` | 库存 Outbox 分片扫描投递 |
| lotteryOrderMQStateJobHandler | dbIdx 列表 | 发奖消息补偿重发（scan 带 LIMIT=100） |

### Caffeine 为什么存在？
StrategySnapshot 是每次抽奖必需、活动运行期基本不变的热点读：
Caffeine L1(60s, max 10000) + Redis L2(600s+jitter120s)。
一致性边界明确为**短 TTL 最终一致**：运营变更后 TTL 内可能读到旧版本，不做广播失效。
其余实体不再有 L1（activity 只留 Redis 单级；award 信息并入快照；ruleTree 已删除）。

### 分库分表为什么存在？
`user_take_activity`、`user_take_activity_count`、`user_strategy_export` 都是 user-centric 高增长数据，
按 `uId` Hash 路由到 2 库（lottery_01/02）；订单表再分 4 张表（_000.._003）。
参与次数表 `UNIQUE(u_id,activity_id)` 保证同一用户在同一活动的次数语义。

## 3. 抽奖概率模型

* 快照构建：BigDecimal `award_rate × 1,000,000` 取整为整数权重；总和 >100% 直接抛异常 fail fast。
* 总和不足 100% 的差额整体划入 `NO_PRIZE`（采样数组尾部一个 null 槽位），保证剩余概率不会重新分配给真实奖品。
* 采样：`ThreadLocalRandom.nextLong(totalWeight)+1`，对前缀和数组二分（奖品种类少，不引入 Alias Method 的复杂度）。
* 运行期命中已售罄奖品 → 本次视为未中奖并告警日志；**不做重新归一化**，其他奖品概率保持配置值不变。

## 4. 状态机

```text
EDIT(1) ──确认就绪──▶ PASS(4) ──调度到期──▶ DOING(5) ──过期/关闭──▶ CLOSE(7)
                          │
                          └──人工下线──▶ CLOSE(7)
```

落地即一条 CAS：`UPDATE activity SET state=:target WHERE activity_id=:id AND state=:expected`，
影响行数为 0 表示并发冲突或非法前置状态。历史 DB 中的提审/撤审/拒绝/开启态没有生产语义，
migration 注释提供归并语句（默认不自动执行 DROP/UPDATE）。

## 5. 测试体系

| 层级 | 内容 | 环境 |
| --- | --- | --- |
| 单元测试（CI 门禁） | WeightedRandomSelectorTest（微精度概率、NO_PRIZE、越界拒绝、边界采样）；RedisActivityDrawProcessGuardTest（锁 token/Lua 释放）；DrawRequestIdempotencyTest（编排层幂等：全流程一次/回放已消费 take/复用未完成 take/异常时释放锁） | 无需外部环境 |
| 手工集成测试（ManualIT） | StockSettlementIdempotencyManualIT（场景 B 并发预扣不超卖、C/D 同一 orderId 二次结算被 UNIQUE 拦截）；DrawExecManualIT 等 legacy 场景 | 本机 MySQL+Redis，`mvn test -Dtest='*ManualIT'` |

运行要求：MySQL（全局库 lottery 13306、分片库 lottery_01/02）、Redis localhost:6379、可选 Kafka localhost:9092；
先执行 `doc/sql/` 基线脚本与 `docs/sql/V2__architecture_simplification.sql`。

## 6. dbRouterForGem 使用记录与已知限制（本次未重写该组件）

1. **Hash 路由算法**：`idx = (size - 1) & (hash ^ hash >>> 16)`，`size = dbCount×tbCount = 8`。
   隐含前提——size 必须为 2 的幂，否则掩码会造成分布倾斜；当前 2库×4表=8 满足。
2. **SQL 替换正则** `(from|into|update)\s+(\w{1,})`：
   * 只对声明了 `@DBRouterStrategy(splitTable=true)` 的 DAO 生效（仅 `user_strategy_export`）；
   * JOIN 右表、子查询内别名前的第二张分表不会被替换 ⇒ 当前 SQL 全部单表访问，无 JOIN 需求；
   * 订单相关查询始终携带 `u_id` 条件，确保在正确分片上执行。
3. **ThreadLocal 清理**：所有手动 `doRouter` 的调用路径都以 try/finally `clear()` 收尾
   （现集中在 UserTakeActivityRepository.takeParticipation/consumeTake、OrderRepository.saveDrawOrderWithTakeLock/scanByShard）。
4. **限制不是 bug 但要知道**：不支持跨分片聚合查询、不支持分布式自增主键以外的全局二级索引；
   这正是本系统把“需要全量扫描”的数据（outbox/补偿）设计为按 shard 显式遍历的原因。
5. 定位：用于理解路由原理的轻量自研组件，**不是**生产级 ShardingSphere 替代品；本次重构不重写该 repo。
