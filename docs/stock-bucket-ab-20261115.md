# Lottery 单奖品库存分桶 PoC 与对照压测（2026-11-15）

## 范围与重要边界

在独立实验分支 `codex/stock-buckets-poc-20261115` 上开发，原分支 HEAD `9b74b82`；操作仅针对本机 Docker 项目 `lottery-rocketmq-perf` 与 loopback 测试启动器。此前存在的未提交性能脚本和诊断文档保留，不覆盖。所有压测原始数据在 `doc/performance/results/stock-buckets-ab-20261115/`（Git 忽略）。

该修改是**可行性验证**，不是已经适用于多活动存量数据的直接上线迁移。真实奖池仍为 5 类奖品，按订单 ID 对每类奖品分 8 个 MySQL 库存桶，不改变 Redis 总库存、中奖概率、抽奖主链路、RocketMQ 消息语义或唯一订单账本。

### 数据模型与原子边界

- 新增 `award_stock_bucket(activity_id,award_id,bucket_id,initial_count,remaining_count)`，复合主键。奖品一经创建，`activity_prize.stock_bucket_count` 持久化为 0（单行）或正数（桶数）；再次重启/更改默认配置也不改变老活动的结算方式。
- `ActivityRepository.addActivityPrizeList` 在 `ActivityDeployImpl.createActivity` 的同一业务事务中插入所有桶，库存按商与余数精确分配，不能遗漏初始库存。
- `ActivityPrizeRepository.settleAwardStock` 先插入 `award_stock_settlement` 唯一账本，按持久化模式走单行或桶更新；相同事务提交与回滚。目标桶耗尽时探测其他桶，所有桶均为零才回滚新账本并返回 `false`。
- 分桶模式不再更新 `activity_prize.award_surplus_count` 物理字段。**该列在分桶模式是初始值，不是剩余库存的直接来源**；生产查询 `queryActivityPrizeList` 与压测审计通过 `SUM(award_stock_bucket.remaining_count)` 读取逻辑余额。若要正式投产，必须评估所有直接 SQL/报表/运维查询，并为存量活动规划迁移及对账。
- 数据库迁移脚本 `docs/sql/V9__stock_buckets.sql` 先行；最初 PoC 的默认值为 0，正式合并前已改为 8；PoC 原始对照基线测试显式传 0、分桶测试显式传 8，历史结果未改变。

### 测试与验收

编译：`bash doc/performance/build.sh` 通过。定向 JUnit：`PerformanceSqlManualIT` 8 项与 `StockBucketLayoutTest` 3 项，合计 **11 项通过、0 失败/错误**，覆盖唯一账本幂等、库存耗尽回滚、目标桶初始为零时重选、Hash 路由与总库存守恒，以及 32 个订单并发争抢仅 8 件库存时绝不超卖；测试运行在专用 MySQL Schema。

相同 Broker 2GiB / MySQL Buffer Pool 128MiB / 单实例 Spring / 8 MQ 消费线程 / 5 奖品各 100万初始库存 / 72 JMeter Threads / 300 目标 RPS / 205s 请求阶段、15s 预热、190s 有效统计 / 65000 用户，入口限流在隔离环境关闭。分桶组仅更改新奖品库存模式 `--lottery.stock.bucket-count=8`，使用同一版本的已编译业务代码，独立活动 ID、用户前缀、Redis Key 与审计样本。

**基线（活动 91115031）**：有效 299.436 RPS，HTTP P95 108ms、P99 398ms，库存结算 290.173/s，MySQL `Innodb_row_lock_waits` 增量 13,750、`Innodb_row_lock_time` 86.724s，Lag 峰值 1,652，库存/发奖最终全部排空，0 HTTP/业务错误，业务逐笔审计 PASS。原始报告 `baseline-300rps-5prizes/` 中 `stability.json` 显示 **8/9 通过、FAIL**：首尾一分钟均值积压增长 889.286 > 299.436 条，不能把业务审计通过算作稳定吞吐通过。

**8 桶实验（活动 91115032）**：有效 **300.075 RPS**，HTTP P95 **103ms**、P99 **513ms**；库存结算 **296.567/s**、MySQL `Innodb_row_lock_waits` **1,841 次**、累计 `Innodb_row_lock_time` **8.391s**；业务积压峰值 **686** 条，首尾一分钟均值增长 **218.143** 条（小于每秒 300.075 条的验收阈值），排空 **5.620s**，零 HTTP/业务错误，**61,475 笔唯一订单和账本逐笔核对 PASS**，两个 RocketMQ 消费组最终 Lag 0，Redis 无未完成库存预扣，且 5 种奖品各有 8 个桶、初始总库存均为 1,000,000，剩余总额与 Redis 一致。预先制定的稳定性检查 **9/9 全部通过，PASS**。

| 指标（同 300 目标 RPS） | 原单行方案 | 每奖品 8 桶 |
|---|---:|---:|
| 实际 HTTP RPS | 299.436 | 300.075 |
| 实际库存结算 RPS | 290.173 | 296.567 |
| MySQL 行锁等待次数 | 13,750 | 1,841（-86.6%） |
| MySQL 行锁累计等待 | 86.724s | 8.391s（-90.3%） |
| 库存异步 P95 保守上界 | 5,016ms | 1,980ms |
| 全链路异步 P95 保守上界 | 5,551ms | 2,546ms |
| HTTP P99 | 398ms | 513ms（变差，不能隐藏） |
| 首尾平均待发奖积压增长 | +889.286 | +218.143 |
| 稳定性 / 完整业务对账 | FAIL 8/9；审计 PASS | **PASS 9/9；审计 PASS** |

SQL digest 单行组的 `activity_prize` UPDATE 共 61,339 次，耗时约 87.957s（锁等待 81.087s）；分桶组 `award_stock_bucket` UPDATE 61,409 次，耗时约 15.003s（锁等待 8.422s），不再执行旧库存行 UPDATE。**影响与代价**：数据库 CPU 平均核占用从 119.8% 上升为 147.4%；HTTP P99 从 398ms 上升至 513ms，并非所有指标优化。结果是单机、同一台 Mac 上按 A→B 顺序串行测得，缓存热度与系统背景负载仍会影响精确幅度，需更严格的多轮交错 A/B 实验才能估计稳态增益分布。

**真实 RocketMQ 幂等恢复**：分桶组结束后，选择一笔已完成订单，在专用测试库把库存结算状态重置为待处理，以模拟「唯一账本和库存事务已提交、订单状态尚未写回时崩溃」；通过真实 `/__perf/replay` 连续补投两次库存及发奖事件。最终订单状态重新变为完成，库存账本数量始终 **61,475**，全部库存桶剩余总额前后均为 **4,938,525**，未发生重复扣库存；原始证据在 `buckets8-300rps-5prizes/replay-idempotency.json`。

### 对照口径与安全注意

严格区分 HTTP 入口 RPS、实际库存结算 RPS、RabbitMQ/Kafka 与 RocketMQ 无关；本次两个 consumer group 均为 RocketMQ。最终结论必须结合 `stability.json`、`audit.json`、原始 `samples.jtl`、`sql-delta.json`、`resource-summary.json` 及真实 MySQL 桶表，而不是只看发送成功或平均 RT。

本次在同一宿主机串行跑 A→B，顺序、磁盘缓存、共享后台负载不可完全消除。PoC 不包括线上多实例部署、存量活动在线迁移和生产级运维报表适配，不能声称已上线或已经具有普适性能容量。

## 最终复测与交付修订（2026-11-15）

本报告上文的 9/9 PASS 仅代表首次 A→B 实验的真实结果。随后完成反向 B→A 实验及 290 RPS、512MiB Buffer Pool 的额外四轮，全部原始数据和失败情况已保留于 `docs/stock-bucket-closure-evidence-20261115.md`，以该文作为**最终性能结论**。

在两个执行顺序下，每奖品 8 桶相比单行库存，MySQL 行锁等待次数分别下降 86.61% 和 86.07%，有重复实验支持热点写入冲突得到缓解；但第二轮分桶 300 RPS 仅通过 8/9 项稳定性校验，290 RPS 连续两轮及 512MiB Buffer Pool 下 300 RPS 连续两轮都未通过稳定性校验，**不能保证单机在当前累积数据量下持续稳定 290 或 300 RPS**。测试过程中真实订单、唯一账本、Redis 和数据库库存均最终对账通过。当前工程收尾重点为可靠的可选分桶功能、兼容旧活动、迁移安全和如实保留容量边界，后续容量规划应以额外独立测试为依据。

收尾补充：MySQL `v_activity_prize_stock_balance` 为分桶与旧模式统一余额视图；分桶启用前校验完整桶布局再预热 Redis；部署及模式回退限制见 `docs/stock-bucket-rollout-20261115.md`。新增孤立库迁移测试 `doc/performance/verify_stock_migration.py` 已验证原模式余额完整保留。
