# 2026-10-13 资源预检及只读性能复诊证据

基线 main：`34cd882`。业务代码、Mapper、索引、持久化与稳定标准未修改；本轮新增诊断工具及报告。完整结论见 [optimization-report.md](../../optimization-report.md)。

## 本轮有效证据与未执行项

| 记录 | 范围与结果 |
|---|---|
| preflight-1 / preflight-2 | 全部专用服务停止时，两段约 60 秒观察发生 33.58 / 81.75 MiB Swap-in |
| mysql-observation | 专用 MySQL 只读窗口发生 597 MiB Swap-in、178.45 MiB Swap-out；不能归因于某个进程 |
| mysql / mysql-confirm | 同一份业务数据，两次只读采集；八张表、两条生产扫描 SQL，每条每表三次 ANALYZE，共 96 次真实执行 |
| mysql-confirm/mysql-audit.json | 341,311 唯一订单与账本、381,439 参与；关联、次数、状态和负库存核对，仅覆盖 MySQL |
| mysql-start.json / mysql-stop.json | 只启动专用 MySQL；60 秒关闭宽限，退出 0，未 OOM，数据卷保留 |
| resource-ownership-audit.json / environment-after.json | 五个背景实例前后身份、状态、限制、端口与挂载不变，专用四服务最终全部停止 |
| experiment-decision.json | 环境停止决定；基线复测、升压、A/B、Kafka 重投、Redis/完整业务审计均未执行 |
| validation.json | 新工具实际运行、语法及原始证据一致性检查；不是业务单元/集成门禁 |

环境停止不是某个 RPS 档位失败，未生成 JTL 或稳定性通过文件。旧轮次（含失败负载）的所有记录均保留。本轮没有性能优化前/后版本，不计算优化倍数。

## 文件解释

- 每个资源目录的 `environment.json` 保存实际限制、项目/服务标签、端口和卷名称；不包含环境变量凭据。`docker-before/after.json` 是端点快照，不是全窗口 CPU 平均。
- `host-samples.jsonl` 保存 vm_stat 原文、实际页大小、时间及进程资源；`resource-summary.json` 保存连续区间换页增量。已有 Swap 存量与主动换页分别记录。
- 每个 MySQL 目录的 `initial-data.json` 保存精确 COUNT、状态分布（第二次采集增加）、实际版本/持久参数；表大小/估算行数另列，不能混用。
- `indexes.tsv` 是实际 Schema 索引，不用迁移文件代替部署事实。
- `lottery_0*-user_strategy_export_00*-scan*.sql` 提取生产 Mapper；对应 `explain.tsv`、`analyze-1/2/3.txt` 为实际结果。mysql 批处理将树中的换行转义为文字 `\n`，汇总展示时才还原。
- `plan-summary.json` 含原树、索引迭代器行数、根节点耗时与八表合计。补偿每次迭代器读取 341,311 行、返回 0；库存扫描读取/返回均为 0。耗时受缓存、执行顺序、换页和诊断方式影响。
- 第二次采集的 `metrics-before/after.json` 保存锁等待与全局计数；只读窗口没有锁等待，不能推广到压力场景。
- 两次 MySQL 计数一致；341,311 为全部历史活动，不是本轮新订单。原优化轮的 155,557 是它的活动子集。

## 复现

仓库根目录执行，结果目录使用尚未存在的名称。先观察资源，不操作其它实例。

```bash
python3 doc/performance/preflight.py doc/performance/results/repeat-preflight/idle-1
python3 doc/performance/preflight.py doc/performance/results/repeat-preflight/idle-2
```

仅在明确归属本次诊断、资源可承受的既有 MySQL 上读取；以下假设原 `lottery-perf` 容器仍存在且已停止。不要执行 init/clean 或重建数据卷。

```bash
set -euo pipefail
cd doc/performance
python3 -c 'from environment import verify; verify()'
docker start lottery-perf-mysql-1
# 等到实际健康状态为 healthy 后执行；未就绪时不把连接异常当成功。
docker inspect --format '{{.State.Health.Status}}' lottery-perf-mysql-1
python3 mysql_diagnosis.py results/repeat-preflight/mysql
docker stop --timeout 60 lottery-perf-mysql-1
docker inspect --format '{{.State.Status}} {{.State.ExitCode}} {{.State.OOMKilled}}' lottery-perf-mysql-1
```

SQL 工具自身不启停容器，只执行 SELECT、SHOW/EXPLAIN 类只读诊断。错误直接传播，已经保存的原始文件不覆盖。新一次执行时数据和缓存可能不同，结果应重新核对，不要求复现相同毫秒数。

资源可信后，完整链路仍按原 [README.md](../../README.md) 使用 build/start/run/analyze/semantics/message_replay/diagnostics/stages/check_stability。80 RPS 原版至少 180 秒，最高候选 300 秒；按原标准逐步升压。索引或业务优化须另做单问题测试、同数据规模 A/B、完整链路和正确性验证，本次诊断不能替代它们。

## 保存范围

仓库保存本索引、资源/环境汇总、两次 SQL 计划汇总、实际索引、初始数据、MySQL 核对及停止/验证记录。vm_stat 原始采样、Docker 端点采样、全部 SQL 和 EXPLAIN/ANALYZE 文件保留本地；完整包为 `raw-results-20261013-rediagnosis.zip`。汇总内也保存执行树及其原始来源文件名，重新核对逐区间资源需本地原始包或重新运行脚本，不凭汇总制造新测量。
