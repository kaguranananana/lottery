# RocketMQ 库存结算瓶颈：反事实对照与优化验证（2026-11-15）

> 实际执行于 MacBook Air M4，独立 Docker 项目 `lottery-rocketmq-perf`。基线源码 HEAD `9b74b82`，分支 `codex/rocketmq-transactional-20261114`。原始数据在 `doc/performance/results/rocketmq-rootcause-20261115/`，包括 JMeter JTL、数据库 SQL digest、每三秒资源与锁采样、业务对账、错误日志。**本报告严格区分「稳定通过」与「重试后最终收敛」。**

## 1. 诊断背景与业务链路

抽奖 HTTP 请求完成 TX1（扣用户参与次数、创建参与记录）与 RocketMQ 半消息+TX2（Redis Lua 预扣、创建订单）后返回。库存消费者根据订单保存唯一库存结算账本，在**同一个 MySQL 事务**里对 `activity_prize` 中由 `(activity_id, award_id)` 唯一定位的一行执行条件库存 UPDATE；事务提交后更新订单库存完成状态，通知发奖消费者，最终落库发奖状态。

一个活动只有五种等概率奖品时，高频订单集中更新五条数据库行。MySQL InnoDB 为 UPDATE 自动取得排他行锁，持锁直到 COMMIT，因此更新同一条记录的多笔库存事务要竞争。前一天已用 `performance_schema.data_lock_waits` 真实看到 `activity_prize.uk_activity_award` 的 X 锁等待；但此前测试中 Broker 内存、事务提交、文件缓存等可能混杂，不能单凭锁等待认为系统总容量固定为 240 RPS。

## 2. 方法：同到达速率，改变热点行数量

现有专用压测控制面 `PerfHarness.setup` 原本已支持 `awardKinds` 和 `awardRate`，但 `run.py` 写死五种奖品，Redis 审计也固定检查五个键。本次仅扩展**测试脚本** `doc/performance/run.py`，新增 `--award-kinds`（默认 5），按奖品种类等概率初始化并逐个审计所有 Redis+MySQL 库存、结算账本、订单与 HTTP 请求 ID。50 奖品并非业务侧正式优化方案，只是「减少热点碰撞概率」的对照变量，不修改真实结算代码与事务边界。

测试仍执行真实 Spring 应用、RocketMQ 事务消息、MySQL、Redis、JMeter 固定速率；每档重新创建独立活动与 65000 个用户，JMeter 72 线程、持续 205 秒、预热 15 秒，真实计量约 190 秒。专用 HTTP 测试入口限流关闭，生产默认限流未改。默认 MySQL 2 CPU / 2GiB，Broker 2 CPU，同步刷盘，MySQL `innodb_flush_log_at_trx_commit=1` / `sync_binlog=1`，在明确标出的实验中仅将 Broker **live container 内存配额**由 1GiB 临时提升为 2GiB，Docker Compose 文件未变。

稳定性沿用仓库 `doc/performance/check_stability.py` 已固定的九项校验：持续窗口≥180秒、目标速率≥95%、HTTP与系统错误率<0.1%、HTTP P99<1秒、积压峰值≤10秒请求量、首尾60秒平均积压增长≤1秒请求量、异步P95上界<10秒、停止后15秒内排空、逐笔业务审计 PASS。

## 3. 核心实测结果

| 方案 | HTTP 测量 RPS | 行锁等待次数 / 累计时间 | 待发奖积压峰值 / 首尾平均增长 | HTTP业务异常 | 结论 |
|---|---:|---:|---:|---:|---|
| 8 消费线程、270 RPS、50 奖品、Broker 1GiB | 269.99 | 1,542 次 / 12.1 秒 | 421 / +140 | 14 个 `0001` | **原测试失败**（发送异常）；重放原请求后 14 笔全部恢复 |
| 8 消费线程、270 RPS、5 奖品、Broker 2GiB | 269.98 | 13,902 次 / 113.5 秒 | 597 / +188 | 0 | **稳定通过 9/9** |
| 8 消费线程、300 RPS、5 奖品、Broker 2GiB | 299.41 | 17,173 次 / 139.3 秒 | 2,533 / +1,540 | 1 个 `0001` | **失败：积压持续增长及业务错误**；原请求重放后恢复 |
| **8 消费线程、300 RPS、50 奖品、Broker 2GiB** | **299.99** | **1,617 次 / 10.1 秒** | **531 / +234** | **0** | **稳定通过 9/9** |

**核心对照**：在相同 300 RPS、8 线程及 Broker 2GiB 的配置下，将随机奖品数由 5 提升至 50，MySQL 行锁等待从 17,173 次降至 1,617 次（约减少 90.6%），累计等待从 139.3 秒降至 10.1 秒（约减少 92.7%）。原 5 奖品的库存消费持续积压，50 奖品无此问题，且后者完整通过预定九项验收。这是库存热行锁竞争具有显著性能影响的有力反事实证据；运行顺序、缓存预热与其他资源变化仍可能影响精确增益，不能解释为严格实验室环境的普适吞吐比例。

300 RPS、50 奖品稳定档：HTTP P99 424ms、库存结算吞吐约 298.15/s、库存 P95 延迟保守上界约 1.206 秒、整体异步 P95 上界 1.86 秒，结束后排空约 7.65 秒；库存和发奖、参与次数、唯一账本、Redis/MySQL 余额完整对账无误。270 RPS、5 奖品稳定档：HTTP P99 550ms、库存 P95 上界约 1.82 秒、结束后排空约 3.39 秒。**因此原报告「最高已验证稳定 240 RPS」仅针对当时原 Broker 1GiB 配置；当前新环境下 270 RPS 已验证稳定。**

## 4. 独立发现：RocketMQ 发送异常与可恢复参与

第一轮 270 RPS、50 奖品、Broker 1GiB 的 55,340 次 HTTP 请求中，55,326 次返回业务成功，另 14 次返回 `HTTP 200` 但业务码 `0001`。应用真实堆栈显示 `RocketMqProducer.sendStock` 抛出 `RemotingSendRequestException`，其 SDK 同步发送默认超时 1,500ms 且显式设置零次失败重试；同时观察到少量发奖 Outbox 首次发送失败。异常中断发生于 TX1 已成功但 TX2 未完成，留下 14 条 `draw_participation.state=0`；Broker 事务回查返回 UNKNOWN，不能把这些直接当成成功或未中奖。

我们直接调用真实 `POST /api/v1/draw`，使用原用户身份与**相同 requestId**重试 14 次，全部返回业务成功 `0000`。后续数据库只读核验：55,340 个参与均已消费、55,340 个订单、55,340 条唯一库存账本，无残留 MQ Lag；证明该类中断的幂等恢复路径**经过真实验证**。独立的 300 RPS、5 奖品轮也发生 1 笔相同错误，并用原 requestId 成功恢复。但**原压测的 HTTP 业务异常不能通过后续重试抹除，严格审计仍判失败**。该异常发生时 Broker 内存采样约 647MiB/1GiB，且没有 OOM，不能把该失败直接归因于 Broker 内存上限。零业务错误与事务恢复能力需要分别评价。

新增 `doc/performance/diagnose_failed.py`：对校验失败的原始测试只读计算 MySQL/积压/错误明细并写入 `diagnostic-only.json`，不会修改原始 JTL、掩盖异常或捏造 PASS。

## 5. SQL 与下一步工程取舍

SQL digest 进一步印证：300 RPS、5 奖品的热点 `activity_prize` UPDATE 累计约 145.1 秒，其中锁等待约 139.3 秒；300 RPS、50 奖品累计约 15.5 秒、其中锁等待约 10.1 秒。与此同时，前一档所有 COMMIT 汇总约 1,662 秒，后一档约 1,329 秒。MySQL 热行竞争显著，但 COMMIT 和订单状态写入开销亦不可忽略。**不能通过简单加线程、删除库存 SQL 或放宽事务一致性要求来宣称优化成功。**

这里刻意没有直接将真实业务中的五种奖品改成五十种，或引入多行库存分桶：前者改变奖品业务模型，后者需要新的分桶选择、Redis/DB 对账、回滚补偿、幂等和一致性语义，必须专门设计与验证。后续优先评估线程配置和 MySQL 缓冲池等低风险、可还原单变量方案，成功后再考虑代码修改或高复杂度结构优化。

## 6. 原始证据与状态

本轮所有目录均位于 `doc/performance/results/rocketmq-rootcause-20261115/`：`rate270-50prizes`（含 `recovery-replay.json`）、`rate270-5prizes-broker2g`、`rate300-5prizes-broker2g`（含 `recovery-replay.json`）、`rate300-50prizes-broker2g`。通过测试有 `summary.json`、`stability.json`、`audit.json`、`sql-delta.json`、`resource-summary.json`；失败测试保留 `run-error.json` 和 `diagnostic-only.json`。不借用历史 Kafka 版本的 222 RPS 进行不具可比性的百分比宣传。该项目在本地执行；截至编写时没有 Git commit/push 和生产配置修改。


## 7. 真实业务不变的低成本优化尝试：线程数与缓冲池

保持 Broker **2 GiB**、单活动 **5 种奖品**、目标 **300 RPS**，独立做两轮控制变量测试，均为约 190 秒测量，**未更改任何生产 Java/SQL 代码**：

| 配置 | HTTP 实际 RPS | 库存结算 RPS | 行锁等待 / 总等待时间 | 待发奖积压峰值 / 首尾增长 | 停流后排空 | 严格稳定性 |
|---|---:|---:|---:|---:|---:|---|
| 8 消费线程、MySQL Buffer Pool 128MiB（原对照） | 299.41 | 因业务错误未出正式值 | 17,173次 / 139.3s | 2,533 / +1,540 | 10.15s | **失败**：1笔业务错误、积压增长 |
| **4 消费线程**、Buffer Pool 128MiB | 298.25 | **212.57** | 11,022次 / 50.0s | **16,277 / +12,635** | **63.16s** | **失败**：9项中4项未通过，完整审计 PASS |
| 8 消费线程、**Buffer Pool 512MiB** | 299.96 | **284.37** | 18,417次 / 148.7s | **2,695 / +1,338** | 9.47s | **失败**：积压增长，其他8项含完整审计 PASS |
| 8 消费线程、**50种奖品**、Buffer Pool 128MiB（对照） | **299.99** | **298.15** | **1,617次 / 10.1s** | **531 / +234** | **7.65s** | **9/9 通过** |

4线程减少同库存行的并发争夺，但库存消费速率降至 213/s，积压愈发严重，因此不能以「锁等待次数下降」冒充吞吐提升。Buffer Pool 从128MiB提升到512MiB后，对照档 MySQL `Innodb_buffer_pool_reads` 由 **83,582 次降至 8,116 次**（仅按独立轮次计数，注意缓存预热/负载顺序影响），但库存结算仍无法持续达到300/s、最后60秒待发奖积压增长过多；因此**缺少数据证明调大数据库缓存能解决库存热点**。这两组失败实验与原始成功档一起保留，并没有为求好看的结果删除失败样本。

**工程决策**：当前最直接、已被实验支持的瓶颈是同一奖品库存行的写入串行化/锁竞争，而无需推翻已有的「Redis 原子预扣、MySQL 事务账本+条件扣减、异步订单状态与发奖」业务正确性设计。为避免「面试包装」牺牲一致性，**此轮不贸然提交库存分桶表、异步汇总库存或取消单库事务的生产实现**。如果确有稳定持续高于300 RPS且奖品数仅5的真实需求，应单独设计库存分桶：保证 `SUM(bucket.stock)` 与账本扣减、Redis总库存、活动配置一致；桶耗尽时允许重选其他桶，不误报售罄；账本幂等/消息重投/失败补偿/部分发奖及数据迁移全部完成 E2E 对账后，再以相同 300 RPS 持续长测验证。否则维持当前更简单的原子库存模型，按实际活动吞吐配置入口限流。

**面试表达口径（约25秒）**：「我压测发现 HTTP 300 RPS 还正常，但少量奖品对应的 MySQL 库存行出现写入竞争，使 RocketMQ 消费者持续积压。我先抓到真实的行锁等待，再做反事实实验：固定 300 RPS，把测试奖品数从5改成50，行锁等待从1.7万次降到1600次，完整链路从不稳定变成通过。随后把消费线程减半和数据库 Buffer Pool 扩容分别试了，都不能让5奖品场景持续稳定。说明真正需要突破的是热点库存更新的串行化；后续可评估库存分桶，但必须保持账本幂等和库存对账。」这个回答只包含真实观察和实验；「50奖品」是验证瓶颈用的测试场景，不是生产上直接部署的优化。


## 8. 复现命令（隔离压测环境，非生产配置）

以下命令均在仓库根目录执行。首次需确保 Docker 镜像已经本地缓存；若 Docker Hub 匿名鉴权暂时失败，可明确使用 `--pull never` 复用已有镜像。必须使用**新的活动 ID 和新的 session/name**，不要重复使用已有测试的用户或订单数据：

```bash
# 仅启动 lottery-rocketmq-perf 这个独立 Compose 项目
docker compose -p lottery-rocketmq-perf -f doc/performance/compose-rocketmq.yml up -d --wait --pull never
docker update --memory 2g lottery-rocketmq-perf-broker-1
bash doc/performance/build.sh

# 独立测试应用开启8个库存/发奖消费者线程；实际端口只监听127.0.0.1
mkdir -p doc/performance/results/repro/app
bash doc/performance/start.sh doc/performance/results/repro/app   --lottery.draw.admission.enabled=false --lottery.rocketmq.consumer-threads=8

# 另开终端执行，确保 --id 以及 --session/--name 不与既有目录冲突
python3 doc/performance/run.py --session repro-unique --name rate300-5 --id 8111598   --threads 72 --duration 205 --warmup 15 --users 65000   --target-rps 300 --ramp 5 --award-kinds 5   --jmeter /tmp/lottery-rocketmq-tools/jmeter-maven/bin/jmeter

# 通过严格审计的运行可继续做资源汇总和稳定性检查
python3 doc/performance/diagnostics.py doc/performance/results/repro-unique/rate300-5
python3 doc/performance/check_stability.py doc/performance/results/repro-unique/rate300-5

# 测试完成后，先停专用Java进程、再恢复临时测试参数并停Docker
# Broker仅本轮临时提升过内存，本次实际退出时已恢复1GiB
```

完整参数也保存在每轮目录的 `parameters.json`、`command.json`，可以直接核实实际运行命令。复现时不得将关闭入口限流的结果冒充生产线上所有限流策略启用后的容量，也不得把更换奖品数的测试当成真实业务库存分桶优化。
