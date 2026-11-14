# Interview Architecture — 面试叙事

> 这份文档用面试语言回答四个问题：为什么这样设计、原设计哪里有问题、为什么做这次减法、每类一致性问题如何解决。重点是工程因果，不是技术名词堆砌。

---

## 1. 一句话定位

这是一个单体部署的高并发抽奖系统。当前约束是用户个人参与次数和活动独立奖池库存；活动直接持有奖品概率与库存，不再经过抽奖配置实体。重构前的版本的问题是“架构复杂度放错了位置”——大量复杂度花在 Factory/State/RPC DTO 这些没有业务因果的样板代码上，而真正决定系统正确性的库存幂等和消费幂等反而存在真实缺陷。

## 2. 原设计哪里有问题（我先做了审计）

`docs/refactor-audit.md` 记录了逐文件审计结论，最重要的三个缺陷：

**① 同一个订单可能扣两次数据库库存（严重）**
消费者先 `UPDATE draw_config_prize SET surplus-1 WHERE surplus>0` 再更新状态、再写 Redis DONE 标记。
任何一步之间 crash，Kafka redelivery 后 `surplus-1` 会再执行一次——它是累加式更新，没有任何账本约束“这个 orderId 已经扣过”。三层 Redis Guard（DONE/PROCESSING/DISPATCHING）看起来很可靠，其实每一层都是缓存语义：TTL 过期、值丢失后全部失效。

**② 历史活动库存同步链路会漂移（已移除）**
参与成功后把 Redis 剩余库存的**绝对值**作为消息发给 Kafka 覆盖 MySQL。消息按 takeId 分区，多个 take 的 99/98/97 并行到达，即使 SQL 里加了 `WHERE surplus > #{v}` 的单调保护，丢一条消息就会造成 Redis 和 MySQL 永久偏差；而这条链路对正确性没有任何贡献——Redis 本来就不超卖，MySQL 只是滞后影子。先前重构已删除该 MQ；2026-11-02 进一步取消全场参与名额限制，删除活动两列和 Redis 预扣/回补。个人次数和奖品库存各自保留完整事实与结算职责。

**③ 库存 key miss 时从 MySQL 回填造成“复活”**
结算异步滞后的窗口里 MySQL 值偏大，如果此刻 Redis key 丢失被回填，等于凭空多出库存。

**④ 其他**：draw 防重锁 value 固定 "1"、finally 直接 DEL（A 超时后误删 B 的锁）；请求只有防重没有幂等，网络超时重试会多消耗一次机会；概率算法 `rate*100→int` 会把 0.1% 截断成 0，另一套算法动态归一化会在奖品售罄时悄悄抬高别的奖中奖率。

## 3. 为什么这次是减法

判断标准很简单：**每一个抽象必须对应一个真实的失败场景或业务差异，否则删掉。**

| 删除 | 理由 |
| --- | --- |
| lottery-rpc + Dubbo + Nacos | 单体部署没有任何外部 RPC consumer；RPC Req→DTO→Domain VO 的三次转换不构成边界。换成 HTTP Controller |
| 规则树引擎（30+ 类 + 3 张表） | 主链路从不经过树，只服务一个演示接口 “用户画像→活动” 决策。抽奖系统的核心复杂度在库存与一致性 |
| 7 个 State 类 + Map 注册 | 生产只用 4→5、5→7 两个转换，DB 层本来就是 CAS。收敛成一个带转换矩阵的 ActivityLifecycleService |
| 两套概率算法 + DrawConfig map | 统一为快照整数权重 + 二分采样；高精度、显式 NO_PRIZE、售罄不改概率 |
| ShortCode / RandomNumeric / IdContext | 只有测试 demo 用。保留 Snowflake 直注 |
| 活动总库存及历史 Partake 库存 MQ | 无全场名额需求，MySQL 活动余量缺完整结算；移除两列、Key 和参与预扣/回补 |
| DONE Redis guard、no-stock Set、award/ruleTree 实体级 Caffeine | 事实来源移到 DB 后 guard 失去意义；实体全量 L1 是为了展示“二级缓存”，现在只有 ActivityPrizeSnapshot 值得 |

当前保留的组件各有明确职责：Redis 做奖池原子预扣和请求锁；Kafka 剥离库存结算与发奖；Outbox 恢复提交后未发送的订单；唯一账本拦截重复结算；Spring 定时任务补偿到期义务；Caffeine 缓存活动奖池快照。MySQL 使用单库，不再做用户分片路由。

## 4. 每类一致性问题怎么解决

### 4.1 高并发库存
Redis 在一个 Lua 脚本内检查活动奖池 Key 存在且余量大于零后 DECR，售罄不写负数。每个活动在 `activity_prize` 中有独立库存；活动就绪时按该活动的奖品库存预热 Key。运行期 Key 缺失显式报错，保留原 take 和请求号，不能用滞后的 MySQL 值回填。最终一致靠消费者事务里的 `activity_prize.award_surplus_count>0` 条件扣减兜底，出现分歧则落 BLOCKED 状态等人工对账；发送 FAIL 仍参与补偿。
缺失奖品库存明确抛出并保留未消费 take；回补不创建缺失 Key，SQL 异常只在确认回滚或事务未开始时归还预扣。

TX1 是“条件扣个人次数 + 创建 take”，TX2 是“消费 take + 中奖订单 + Outbox INIT”，TX3 是“唯一账本 + MySQL 奖品扣减”。三个本地事务没有合并。NO_PRIZE 和奖品售罄都消耗已取得的个人机会；次数用完才拒绝新参与。预扣后 JVM 退出、事务结果未知和回补失败仍需对账，不能声称 Redis/MySQL 原子一致。

### 4.2 请求幂等（重复点击 ≠ 幂等）
重构前只有防重。现在 API 必带 `requestId`：

* 快路径：查 `draw_participation.request_id`（UNIQUE 兜底）。
  已消费 → 用 take 关联列查出订单，回放原来的中奖结果或未中奖结果；
  未消费 → 复用该 takeId 继续抽奖，绝不新建记录、不再扣次数。
* 慢路径（新请求）：先从 DB 核对同用户活动未完成记录；B 遇到 A 返回 D007 与 pendingRequestId=A，不能取得 A 的 take。确认没有未完成记录才参与+下单；请求唯一键与未完成唯一键共同兜底并发。具体客户端恢复及可执行迁移见 [接口说明](draw-request-idempotency.md)。
* 并发防重入锁只是性能优化：value 是随机 token，释放走 Lua compare-and-delete，
  “A 超时、B 获锁、A finally 释放” 不再误删 B 的锁。

### 4.3 MQ 消费幂等（最核心）
消费库存事件的本地事务：

```sql
BEGIN;
INSERT INTO award_stock_settlement(order_id, ...) ...;   -- UNIQUE(order_id)
-- duplicate key? → COMMIT 提前结束：该订单早已结算，直接 SUCCESS
UPDATE activity_prize SET award_surplus_count = award_surplus_count - 1
WHERE activity_id=? AND award_id=? AND award_surplus_count > 0;
COMMIT;
```

crash 发生在任何一步之后，redelivery 都会撞上唯一键——**同一 orderId 永远只扣一次数据库库存**，
这是用数据库不变量而不是分布式锁实现的 exactly-once 效果（扣减层面）。
PROCESSING Redis 锁保留为并发优化，但文档里明确它不是事实来源。

### 4.4 Outbox 可靠投递
订单、库存 Outbox 与首发代数在同一事务提交。提交后由有界工作池实时发送，Spring 定时任务按独立重试时间领取到期义务；失败按代数退避，SENT 超时后允许重发。CAS 阻止迟到回调覆盖 COMPLETE/BLOCKED，重复消费由 4.3 的账本和订单状态收敛。索引、租期和批量边界见 [订单 Outbox](outbox-delivery.md)。

### 4.5 发奖幂等
处理器层只留一层 `AwardFulfillmentHandler`（优惠券/兑换码/实物/虚拟的差异是真实业务差异），
每个 handler 以 `orderId` 作为发奖幂等键；本地以 `award_fulfillment_status != 1` CAS 标记兜底，
重复投递安全。README 明确当前 handler 是 adapter stub，避免假装完成了第三方接入。

## 5. 为什么 DDD 分层保留但没有仪式感

四层职责清晰、依赖方向正确，但我知道过度追求纯净会把代码变成转发壳子：
domain 里原本手写的 `dbRouter.doRouter()` + `TransactionTemplate` 全部下沉到 repository 实现，
domain 服务现在只描述业务动作；application 层保留编排价值（幂等回放、MQ 触发）。
订单创建的“锁定 take + 写单”必须同事务，这就是 Outbox 写入点，放在 repository 一个方法里比放在
两层五个类里诚实得多。

## 6. 目前还有哪些边界（主动交代）

* Redis 全面故障时，运行中的活动无法继续抽奖（fail closed），需要人工执行恢复/对账，没有自动化的跨存储对账作业。
* 历史分片库需要按 V5、V6、V7、V8 的旁路迁移步骤切换；新模型和新库使用单 MySQL 库。迁移步骤及停写边界见 [V8 切换](activity-prize-pool.md)。
* 结算 FAIL 状态的处理目前是告警+人工对账，未做自动化差异修复。
* V8 实测在 120/150 RPS 长测中未通过持续容量门槛；当前性能证据与适用边界见 [V8 实测](activity-prize-pool.md#2026-11-13-实测) 和 [Outbox 回写实验](../doc/performance/v8-outbox-write-report.md)。
