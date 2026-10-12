# 2026-10-12～13 独立优化证据

[优化报告](../../optimization-report.md)给出锁机制、三个独立改动、性能观测、正确性边界与面试素材。首次 `20261012-final` 没有覆盖。

## 有效对照与诊断记录

- `base-c30` → `quota-c30` → `index-c30`：30 并发、20 秒预热、60 秒测量，依次只改变次数初始化和库存索引；主动换页及数据体量增长使宏观提升比例不可认证。
- `quiet-before-rate80` → `quiet-after-rate80`：30 压测线程、80 RPS、20 秒预热、180 秒测量，无本轮 Maven/SQL 验证任务并行。两侧库存字段修复一致，仅通过测试类覆盖目录切换 Outbox 预读。宏观环境仍有 Swap，查询次数 4→3 是直接可核验结果。
- `index-rate80` / `outbox-rate80`：早期诊断，前者与本轮验证任务短暂重叠，不把 970→391 的峰值变化用作因果收益。
- `outbox-rate80-confirm`：300 秒持续复核通过；最高稳定容量没有认证。
- `consumer-profile`：按真实 topic、任务及发送回调区分 SQL，另计原生 acknowledge 耗时；用于阶段诊断，不与旧监控版本作性能归因。
- `mapping-smoke` / `semantics` / `message-replay`：最终业务版本创建、热点/超时/归属和真实 Kafka 重投审计。
- `finite-activity` / `finite-award`：1 万用户耗尽触发旧 JMeter 驱动沿用上次变量，均排除有效压测；保存 `measurement-validity.json` / 原始错误。充足用户重跑另存，不改旧结果。
- `seed-exhaustion`：故意只提供一个用户，证明修复后的驱动立即停止并明确非零退出，不能当作正常负载结果。
- `final-finite-activity` / `final-finite-award`：最终业务版本、修复后的驱动、10 万用户，100 个活动中奖订单及 50 个奖品中奖订单全部完成，余量没有超卖。`finite-award-confirm` 是中间的驱动重跑诊断。

## 关键文件

| 文件 | 内容 |
|---|---|
| `environment.json`、`parameters-before.tsv`、`host-before.txt` | 专用容器实际限制、持久化和隔离参数、宿主机初始换页状态 |
| `indexes-before.tsv`、`explain-before.tsv`、`stock-index-after.tsv` | 现有迁移与实际 Schema 差异、库存与订单执行计划 |
| `lock-probe.json`、`lock-excerpts.json` | 两连接屏障复现、真实等待/阻塞事务和锁索引/数据 |
| `comparisons.json` | 各轮实际汇总、阶段/SQL/资源、环境限制，不自动计算提升率 |
| `validation.json`、`aggregate.json`、`runtime-variants.json` | 54 项自动化测试、跨活动业务审计、各轮运行变体 |
| `shutdown-audit.json` | 原容器一秒宽限导致退出 137；恢复后事实一致，60 秒宽限正常退出 0 |
| 每轮 `parameters.json` / `plan.jmx` / `summary.json` / `audit.json` | 实际负载、完成速率与分位数、库存/订单/账本/Kafka 对账 |
| 每轮 `stage-summary.json` / `sql-delta.json` / `resource-summary.json` | 客户端阶段、MySQL Digest 增量、CPU/连接/行锁/换页 |
| 每轮 `stability.json` / `http-end.json` / `drain.json` | 既定完整链路判断、入口停止时积压和最终排空 |

## 复现一个变量

需要现有 README 所列工具。以下在专用空栈或已经确认的专用栈运行；已有 schema 不执行 init，不删除历史数据。两个终端分别运行应用和负载，上一轮全部排空后再停止 JVM。

```bash
# 只在新实例执行 environment.py init；已有历史实例直接启动原容器。
bash doc/performance/build.sh
python3 doc/performance/environment.py stock-index
bash doc/performance/prepare_outbox_before.sh

# 终端 A：预读前 worker 覆盖，其它类与当前构建一致。
bash doc/performance/start.sh doc/performance/results/repeat doc/performance/.variants/outbox-before/classes
# 终端 B：测试期间不运行 Maven/SQL 用例，不关闭用户其它应用。
python3 doc/performance/run.py --session repeat --name quiet-before-rate80 --id 740001 --threads 30 --duration 200 --warmup 20 --target-rps 80
python3 doc/performance/stop.py doc/performance/results/repeat

# 终端 A：当前 worker，资源参数完全相同。
bash doc/performance/start.sh doc/performance/results/repeat
# 终端 B：第二个独立活动，同一数据规模和场景。
python3 doc/performance/run.py --session repeat --name quiet-after-rate80 --id 740002 --threads 30 --duration 200 --warmup 20 --target-rps 80
python3 doc/performance/diagnostics.py doc/performance/results/repeat/quiet-before-rate80 doc/performance/results/repeat/quiet-after-rate80
python3 doc/performance/stages.py doc/performance/results/repeat/quiet-before-rate80 doc/performance/results/repeat/quiet-after-rate80
python3 doc/performance/check_stability.py doc/performance/results/repeat/quiet-before-rate80 doc/performance/results/repeat/quiet-after-rate80
```

覆盖目录只包含本轮移除预读前的 worker 字节码，来源提交按标题检索；脚本需要保留该 Git 历史，检索不到显式失败。它不回退工作区，不改变字段修复或事务/消费者。`stock-index` 复用已有 SQL 的库存部分，不混入发奖补偿索引。两个 JVM 必须依次运行，不能同时抢占 Kafka 分区。

```bash
# 锁机制重现只建立新 fixture，三秒持锁时间不是业务收益。
python3 doc/performance/lock_probe.py doc/performance/results/repeat/lock-probe.json
# SQL 回归用例只操作自己创建的专属 schema。
mvn -q test
mvn -q test -Dtest=PerformanceSqlManualIT -Dsurefire.failIfNoSpecifiedTests=false -Dlottery.perf.mysql=jdbc:mysql://127.0.0.1:13329/
# 最终版实际 HTTP 及 Kafka 重投，ID 必须尚未使用。
python3 doc/performance/semantics.py --session repeat --id 740101
python3 doc/performance/message_replay.py --session repeat
```

原始 JTL、完整锁/资源采样、逐 ID 导出、日志和回归失败堆栈保留本地，另打包 `raw-results-20261012-optimization.zip`，不提交 Git。仓库汇总可复核计数和结论；重新计算原始分位数、逐 ID 审计或采样窗口需要原始文件。新脚本重新执行会生成相同结构的证据，不凭汇总文件伪造原始数据。
