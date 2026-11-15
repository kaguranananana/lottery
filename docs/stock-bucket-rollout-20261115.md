# Lottery 库存分桶：部署、对账和回退说明（2026-11-15）

## 上线边界

本改动为按活动奖品库存写入路径分桶。中奖概率与奖品种类不变，入口 Redis 仍维护每件奖品的总库存，RocketMQ 投递结算事件，唯一结算账本和库存扣减仍处于同一个 MySQL 本地事务。仅新建奖品受 LOTTERY_STOCK_BUCKET_COUNT 影响，存量奖品遵循数据库持久化的 stock_bucket_count 标记。

配置为 0（默认）时使用旧的 activity_prize 单行扣减；配置为 8（此次实测值）时，新奖品创建 8 个桶，实际结算只更新 award_stock_bucket.remaining_count。允许配置 0–64；8 并不代表所有环境的最优值。

**重要：分桶活动的 activity_prize.award_surplus_count 列不再逐笔更新！** 它在此模式下保存初始化时的值，不能据此查询实时剩余库存。统一查询视图 v_activity_prize_stock_balance.remaining_count 才是正确读模型；活动奖池内部 Mapper 已按桶求和。

## 升级步骤

1. 备份 MySQL，确认版本、消息和补偿状态；绝不能用压测脚本直接操作业务生产库。
2. 先在目标库执行 docs/sql/V9__stock_buckets.sql **一次**，增加库存模式字段、桶表、外键/检查约束、统一查询视图。旧行默认 0，原库存保持不变。此脚本为单次版本迁移，不支持无条件重复执行。
3. 部署新应用时保持 LOTTERY_STOCK_BUCKET_COUNT=0，验证老活动的库存结算、库存读取和发奖逻辑不变。
4. 确认兼容后，设置 LOTTERY_STOCK_BUCKET_COUNT=8 创建**新活动**，不要直接修改已经运行的活动模式。新活动奖品与桶在同一 DB 事务创建，READY→RUNNING 前会检查桶数及初始总量守恒，再预热 Redis。
5. 在无在途消息、无待补偿订单的收敛状态下，对比库存总量、唯一结算账本数量、Redis 余额，应满足“初始总量－数据库逻辑剩余＝结算账本数”，以及 Redis 余额等于数据库逻辑剩余。

**启动安全检查**：桶缺失或初始总量不等于奖品库存时拒绝活动启动，避免 Redis 预热部分库存。禁止直接把数据库状态改为 RUNNING 绕过生命周期。

## 运维核对 SQL（替换 12345 为活动 ID）

~~~sql
SELECT activity_id,award_id,award_count,stock_bucket_count,remaining_count
FROM lottery.v_activity_prize_stock_balance
WHERE activity_id=12345;

SELECT p.activity_id,p.award_id,p.stock_bucket_count,
       COUNT(b.bucket_id) AS actual_buckets,
       COALESCE(SUM(b.initial_count),0) AS initial_sum,
       COALESCE(SUM(b.remaining_count),0) AS left_sum
FROM lottery.activity_prize p
LEFT JOIN lottery.award_stock_bucket b
  ON b.activity_id=p.activity_id AND b.award_id=p.award_id
WHERE p.activity_id=12345
GROUP BY p.activity_id,p.award_id,p.stock_bucket_count;

SELECT v.award_id,v.award_count-v.remaining_count AS settled_stock,
       (SELECT COUNT(*) FROM lottery.award_stock_settlement s
        WHERE s.activity_id=v.activity_id AND s.award_id=v.award_id) AS ledger_count
FROM lottery.v_activity_prize_stock_balance v
WHERE v.activity_id=12345;
~~~

发生账本不一致时，先停止新的业务请求、处理在途 RocketMQ 消息和订单补偿，逐笔核对结算账本；不可手工调整桶值掩盖差异。异步结算期间 Redis 与 MySQL 暂时不同步正常，必须在全部事件收敛后才比较。

## 回退和升级边界

如果尚未创建分桶奖品，最安全的功能回退是恢复 LOTTERY_STOCK_BUCKET_COUNT=0，保留代码与 V9 结构。

如果已有分桶活动运行，或者尚有待处理库存消息，**不能把 stock_bucket_count 强制改 0，也不能直接回滚到完全不认识库存桶的旧版本**。否则可能出现同一个活动有的订单扣桶、有的扣父行，库存双计或超卖。必须先暂停活动、收敛所有消息、核对账本、安排经过测试的数据转换；当前代码未实现运行中的库存模式转换，因此不支持此类在线回退。

V9 是前向兼容数据库变更，不是可以在分桶活动运行中无损 DROP 的“可逆迁移”。保留库存桶数据和兼容代码比删除数据更安全。

## 性能证据与限制

详情见 docs/stock-bucket-ab-20261115.md 和本地原始数据 doc/performance/results/stock-buckets-ab-20261115/。此次 300 RPS 使用隔离 MySQL、Redis、RocketMQ、单个 Spring 进程，并关闭 HTTP 入口限流；生产默认入口限流需要独立校验。任何单次压测结果都不能直接推导真实线上 SLA。
