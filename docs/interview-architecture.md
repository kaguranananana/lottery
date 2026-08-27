# Interview Architecture — 面试叙事

> 这份文档用面试语言回答四个问题：为什么这样设计、原设计哪里有问题、为什么做这次减法、每类一致性问题如何解决。重点是工程因果，不是技术名词堆砌。

---

## 1. 一句话定位

这是一个单体部署的高并发抽奖系统。重构前的版本的问题是“架构复杂度放错了位置”——大量复杂度花在 Factory/State/RPC DTO 这些没有业务因果的样板代码上，而真正决定系统正确性的库存幂等和消费幂等反而存在真实缺陷。

## 2. 原设计哪里有问题（我先做了审计）

`docs/refactor-audit.md` 记录了逐文件审计结论，最重要的三个缺陷：

**① 同一个订单可能扣两次数据库库存（严重）**
消费者先 `UPDATE strategy_detail SET surplus-1 WHERE surplus>0` 再更新状态、再写 Redis DONE 标记。
任何一步之间 crash，Kafka redelivery 后 `surplus-1` 会再执行一次——它是累加式更新，没有任何账本约束“这个 orderId 已经扣过”。三层 Redis Guard（DONE/PROCESSING/DISPATCHING）看起来很可靠，其实每一层都是缓存语义：TTL 过期、值丢失后全部失效。

**② 活动库存异步同步链路会漂移**
参与成功后把 Redis 剩余库存的**绝对值**作为消息发给 Kafka 覆盖 MySQL。消息按 takeId 分区，多个 take 的 99/98/97 并行到达，即使 SQL 里加了 `WHERE surplus > #{v}` 的单调保护，丢一条消息就会造成 Redis 和 MySQL 永久偏差；而这条链路对正确性没有任何贡献——Redis 本来就不超卖，MySQL 只是滞后影子。整个链路是负资产。

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
| Partake 库存 MQ 整链 | 见上文②，纯负资产 |
| DONE Redis guard、no-stock Set、award/ruleTree 实体级 Caffeine | 事实来源移到 DB 后 guard 失去意义；实体全量 L1 是为了展示“二级缓存”，现在只有 StrategySnapshot 值得 |

留下的全部有明确职责：Redis（原子扣减+锁）、Kafka（慢操作剥离+重试）、Outbox（消息丢失）、Ledger（重复消费）、XXL-Job（集中调度分片补偿）、Caffeine（快照热点读）、分库分表（user-centric 数据路由）。

## 4. 每类一致性问题怎么解决

### 4.1 高并发库存
Redis `DECR` 为准实时控制，扣到 -1 立即 INCR 归还并判售罄，天然无超卖；
活动启动时由 XXL-Job 用 SETNX 预热所有库存 key（幂等）；运行期 miss **fail closed**：
宁可返回“系统繁忙”也不能用滞后的 MySQL 值把库存变出来。最终一致靠消费者事务里的
条件更新 `surplus>0` 兜底，出现分歧则落 FAIL 状态等人工对账——错误暴露而不是掩盖。

### 4.2 请求幂等（重复点击 ≠ 幂等）
重构前只有防重。现在 API 必带 `requestId`：

* 快路径：查 `user_take_activity.request_id`（UNIQUE 兜底）。
  已消费 → 用 take 关联列查出订单，回放原来的中奖结果或未中奖结果；
  未消费 → 复用该 takeId 继续抽奖，绝不新建记录、不再扣次数。
* 慢路径（新请求）：参与+下单正常流程，唯一约束在并发同 requestId 双写时保证只有一个 take 生成。
* 并发防重入锁只是性能优化：value 是随机 token，释放走 Lua compare-and-delete，
  “A 超时、B 获锁、A finally 释放” 不再误删 B 的锁。

### 4.3 MQ 消费幂等（最核心）
消费库存事件的本地事务：

```sql
BEGIN;
INSERT INTO award_stock_settlement(order_id, ...) ...;   -- UNIQUE(order_id)
-- duplicate key? → COMMIT 提前结束：该订单早已结算，直接 SUCCESS
UPDATE strategy_detail SET award_surplus_count = award_surplus_count - 1
WHERE strategy_id=? AND award_id=? AND award_surplus_count > 0;
COMMIT;
```

crash 发生在任何一步之后，redelivery 都会撞上唯一键——**同一 orderId 永远只扣一次数据库库存**，
这是用数据库不变量而不是分布式锁实现的 exactly-once 效果（扣减层面）。
PROCESSING Redis 锁保留为并发优化，但文档里明确它不是事实来源。

### 4.4 Outbox 可靠投递
订单创建与 `stock_mq_state=INIT` 在同一事务；调度任务扫 `IN(0,2)` 投递并落 SENT；
发送失败留 FAIL 下轮重投。crash 在“发送成功落 SENT 前”最多导致一次重复投递——由 4.3 的账本消化。
`(stock_mq_state, create_time)` 联合索引支撑扫描查询，invoice 补偿 scan 加了 LIMIT 防全表拖库。

### 4.5 发奖幂等
处理器层只留一层 `AwardFulfillmentHandler`（优惠券/兑换码/实物/虚拟的差异是真实业务差异），
每个 handler 以 `orderId` 作为发奖幂等键；本地以 `grant_state != 1` CAS 标记兜底，
重复投递安全。README 明确当前 handler 是 adapter stub，避免假装完成了第三方接入。

## 5. 为什么 DDD 分层保留但没有仪式感

四层职责清晰、依赖方向正确，但我知道过度追求纯净会把代码变成转发壳子：
domain 里原本手写的 `dbRouter.doRouter()` + `TransactionTemplate` 全部下沉到 repository 实现，
domain 服务现在只描述业务动作；application 层保留编排价值（幂等回放、MQ 触发）。
订单创建的“锁定 take + 写单”必须同事务，这就是 Outbox 写入点，放在 repository 一个方法里比放在
两层五个类里诚实得多。

## 6. 目前还有哪些边界（主动交代）

* Redis 全面故障时，运行中的活动无法继续抽奖（fail closed），需要人工执行恢复/对账，没有自动化的跨存储对账作业。
* dbRouterForGem 只支持简单单表路由（正则替表名），JOIN 与非 2 的幂的分片数不可用——已知限制写在 architecture 文档，当前业务 SQL 全部单表可满足。
* 结算 FAIL 状态的处理目前是告警+人工对账，未做自动化差异修复。
* 性能数字：仓库里没有任何宣称值；jmeter 目录保留脚本骨架供复现压测。
