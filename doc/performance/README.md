# Lottery 实际性能压测复现

## 当前 RocketMQ 分支

使用 MySQL 13349、Redis 17579、RocketMQ NameServer 29876 / Broker 20911 和应用 18190。压测启动器使用 JDK 17，应用仍以 Java 8 为编译目标。完整 JMeter 5.6.3 或等价的 Apache Maven 发行组件均可执行原 JMX。

```bash
python3 doc/performance/environment.py up
bash lottery-interfaces/src/test/resources/mq-e2e/create-topics.sh lottery-rocketmq-perf-broker-1 20911
python3 doc/performance/environment.py init
bash doc/performance/build.sh
bash doc/performance/start.sh doc/performance/results/rocketmq-run --lottery.draw.admission.enabled=false
```

在另一个终端执行，每轮使用新的活动号。40、80、120 的 duration 为 205，150 为 315；应用隔离测量关闭准入，生产阈值由主配置管理。

```bash
python3 doc/performance/run.py --session rocketmq-run --name rate40 --id 810140 --target-rps 40 --threads 72 --duration 205 --warmup 15 --ramp 5 --users 65000 --jmeter /path/to/jmeter
python3 doc/performance/check_stability.py doc/performance/results/rocketmq-run/rate40
python3 doc/performance/diagnostics.py doc/performance/results/rocketmq-run/rate40
```

阶段采集包括 JTL、MySQL digest、库存和发奖完成时间、RocketMQ 两组实际队列位点、Redis 与数据库终态对账。生产消费者位点周期性提交，采样 Lag 包含已处理但尚未提交位点的消息。历史 Kafka 报告使用当时的代码、启动器和采样口径。

## Kafka 历史压测

V8 使用七表和活动库存键；本轮对照见 [V8 实测](../../docs/activity-prize-pool.md#2026-11-13-实测)。V7 及更早的历史压测结果见 [单库与入口限流报告](../../docs/single-db-load-test.md)。历史报告使用当时的代码和数据，复现旧实验时检出对应历史版本。当前迁移顺序见 [V8 活动独立奖池](../../docs/activity-prize-pool.md)。

后续定向优化、当前字段修复及压测驱动数据耗尽修复见 [优化报告](optimization-report.md) 和 [本轮证据/复现步骤](results/20261012-optimization/README.md)。本文的首次测量数字和 NULL 字段描述对应原始测试版本，原始结果未覆盖。

2026-10-13 的补偿索引同数据/反向实验及更高持续负载见 [持续容量与扫描证据](results/20261013-capacity-scan/README.md)。该结果使用历史补偿索引迁移；当前空环境由七表 Schema 直接建立重试索引。已有 V7 实例请另建测试环境或按 V8 迁移。`invoice_scan_compare.py <新目录> --repeats 6` 在应用停止且义务排空后交替比较索引路径，记录实际执行树、计数/状态及换页端点；不把 SQL 局部收益当整体吞吐倍数。

后续会话缓存对照、驱动事务回归修复及持续容量边界见 [JDBC 与容量复核](results/20261013-jdbc-session/README.md)。当前单库 JDBC URL 使用 `useLocalSessionState=true`；Connector/J 已修复至8.0.29，会话状态须经 JDBC API 管理。不要仅复制参数到旧8.0.28驱动；真实回滚失败及最小修复见 [驱动修复记录](results/20261013-jdbc-session/driver-fix.md)。

测试启动器只额外装配本机控制面，`POST /api/v1/draw`、活动创建/启动、库存、事务、Kafka、Outbox 和本地模拟发奖均执行仓库的生产实现。未接入真实第三方奖品渠道；最终版本运行实际 Spring 调度和提交后实时投递，压测启动器只提供合成认证和采集。中间版本采用一秒调度模拟，证据独立保留。

## 压测前只读资源与 SQL 诊断

新一轮停止记录及实际扫描计划见 [20261013-rediagnosis](results/20261013-rediagnosis/README.md)。先观察主动换页增量，结合背景负载判断是否能可靠归因；已有 Swap 占用不是单独的停止依据。以下工具不启动、停止、清理或修改任何实例，输出目录须尚不存在。

```bash
python3 doc/performance/preflight.py doc/performance/results/my-run/preflight-1
python3 doc/performance/preflight.py doc/performance/results/my-run/preflight-2
# 专用 MySQL 已核实身份且正常启动后执行；不执行 init/clean。
python3 doc/performance/mysql_diagnosis.py doc/performance/results/my-run/mysql
```

预检默认约 60 秒、5 秒间隔，保存实际页大小、vm_stat 原文、Swap 增量、CPU/内存与容器归属/限制。SQL 工具提取生产 Mapper，保存单表 EXPLAIN/三次 ANALYZE、精确初始数量、持久化参数及 MySQL 核对；它不替代 Redis/Kafka/HTTP 审计。环境持续受不可控制的换页或共享负载干扰时，保留结果并停止容量与收益认证，不修改稳定性阈值。

## 环境与首次准备

需要 Docker Compose、JDK 17（运行现有 Java 8 目标字节码）、Maven、Python 3 和 JMeter 5.6.3。绘图额外需要 matplotlib。机器资源与软件版本以每次测试会话的 `environment.json` 为准。应用/JMeter 在宿主机，中间件在独立 Docker；MySQL 13339、Redis 17479、Kafka 29192，应用 18190。端口均与现存 E2E 栈分开。

```bash
# 从仓库根目录执行；init 拒绝已有 schema，不自动覆盖历史结果。
python3 doc/performance/environment.py up
python3 doc/performance/environment.py init
bash doc/performance/build.sh
python3 doc/performance/make_plan.py
# 另一个终端保持前台启动；重定向前先创建自选结果目录。
mkdir -p doc/performance/results/my-run
bash doc/performance/start.sh doc/performance/results/my-run > doc/performance/results/my-run/application.log 2>&1
```

每次新建活动先调用真实创建服务，再执行 READY→RUNNING（事务内 SETNX 预热），不直接修改状态为运行中。每轮默认预置10万用户，写入单一额度表；每用户10次机会，每次基准请求使用新用户、新 requestId。活动无总库存，五种模拟券每种100万库存、20%概率，100%中奖以覆盖较重的订单与异步主链路。已预置用户仍执行真实的额度查询、条件扣减与参与写入。

## 实际运行

```bash
# 指定本机 JMeter 可执行文件；以下ID在全新环境使用。
python3 doc/performance/run.py --session my-run --name smoke --id 720001 --threads 2 --duration 12 --warmup 5 --users 2000 --jmeter /path/to/jmeter
python3 doc/performance/run.py --session my-run --name c10 --id 720010 --threads 10 --jmeter /path/to/jmeter
python3 doc/performance/run.py --session my-run --name c30 --id 720030 --threads 30 --jmeter /path/to/jmeter
python3 doc/performance/run.py --session my-run --name c50-confirm --id 720050 --threads 50 --jmeter /path/to/jmeter
# 根据异常、资源和积压决定是否继续100/200，禁止带着上轮积压开始下一轮。
# 固定到达速率与无等待阶梯分开；每个目标速率另建活动。
python3 doc/performance/run.py --session my-run --name rate60 --id 720060 --threads 30 --duration 320 --warmup 20 --target-rps 60 --jmeter /path/to/jmeter
# 使用原请求号的并发恢复场景需在限流关闭进程运行。
python3 doc/performance/semantics.py --session my-run
# 同用户与真实 MySQL 慢 SQL 场景。使用不同活动号。
python3 doc/performance/run.py --session my-run --name hotspot --id 720070 --threads 12 --target-rps 60 --users 10000 --quota 1000 --single-user
python3 doc/performance/run.py --session my-run --name slow --id 720080 --threads 72 --ramp 1 --target-rps 350 --users 40000 --slow-ms 300
# 奖品库存竞争：总共50份奖品；售罄命中仍为D001并消耗个人次数，不重新抽样。
python3 doc/performance/run.py --session my-run --name finiteAward --id 728002 --threads 30 --duration 15 --warmup 0 --stock 100000 --award-stock 10 --users 100000 --jmeter /path/to/jmeter
python3 doc/performance/analyze.py doc/performance/results/my-run/c10 doc/performance/results/my-run/c30 doc/performance/results/my-run/c50-confirm
```

无等待阶梯默认爬升5秒、预热15秒、测量60秒，总80秒。统计从首条采样开始，按请求**完成时间**落入稳定窗口计算 RPS，分位数使用最近秩。JMeter结束前最后不足一秒的窗口可能低估完成量；所有阶梯采用同一方法，不把提前开始但未完成的请求算吞吐。

`0000`为同步下单成功，`D001`为完成的未中奖抽奖，`D006/D007`分别为处理中/原请求未完成，活动售罄码 D004 已删除。系统异常另计，HTTP 200 不等于业务成功。正常基准只允许0000/D001；幂等成功回放只算一个新操作。奖品售罄后的未中奖RPS不能替代成功下单容量。`--stock` 现在只表示每种奖品的默认总量，`--award-stock` 仍可指定奖品总量；二者都不限制活动参与总数。

限流开关对照使用同一隔离中间件、同一 JMX 和新活动。停止已记录 PID 的压测应用后，用 `start.sh <新日志目录> --lottery.draw.admission.enabled=false` 启动对照，测试完成可用同样步骤切回默认。压测启动器绑定 loopback，在测试进程内将合成账户映射到 Servlet Principal。测试头只在该启动器生效。统计保留全局 L001、用户 L002、本机 L003 及 Redis 故障 L004。

## 原始证据与采样

每轮保存参数、真实命令、JTL（业务码/request/take/order）、初始化值、3秒级 JVM/GC/Hikari/Tomcat/宿主进程/Docker采样、约9秒级 MySQL/Redis/Kafka/异步采样、SQL digest 前后值、停止入口后的积压、2秒级排空探针、全量参与/订单/账本ID和对账结果。采样本身会产生少量开销。个别容器/SQL采集耗时会拉长周期，原始 `collectionMs` 可核实实际间隔。

审计逐项检查用户与请求身份、take与order关联、次数消耗、各奖品库存与账本、全局重复订单、未消费参与、BLOCKED、库存/发奖最终状态、Kafka八分区位点和Lag。数据库时间戳精度为秒，HTTP→异步完成延迟报告一秒宽的上下界；没有伪造毫秒精度。

2026-11-02 工具已同步单库存语义：参与数与个人次数核对，中奖订单与各奖品 Redis/MySQL 库存及账本核对，不再读取活动库存列或 Key。奖品总量/剩余量当前均为 Integer 并真实持久化；历史 NULL 总量和双库存压测只保留在原始结果，不回填或改写旧证据。当前分析器面向单库新会话。

## 停止与安全清理

```bash
# 先停止专用应用并核对其PID身份，再停止独立栈，数据库与原始结果保留。
python3 doc/performance/stop.py doc/performance/results/my-run
python3 doc/performance/environment.py stop
# 需要从空库重跑时才删除专用Docker项目。标签和本机绑定端口必须匹配。
python3 doc/performance/environment.py clean
```

清理仅操作 `lottery-single-perf`，不会停止 `lottery-mq-e2e` 或其它实例。生成的结果目录不自动删除。再次复现使用新的 session 目录，并在专用栈清空后初始化。生产业务代码、数据库、Redis、Kafka和Git历史保持原样。

## 汇总与曲线

```bash
# 绘图环境独立于压测应用，避免安装图形依赖改变生产Maven依赖。
python3 -m venv /tmp/lottery-perf-charts
/tmp/lottery-perf-charts/bin/pip install -r doc/performance/requirements-plot.txt
python3 doc/performance/diagnostics.py doc/performance/results/my-run/c10 doc/performance/results/my-run/c30 doc/performance/results/my-run/c50-confirm
python3 doc/performance/check_stability.py doc/performance/results/my-run/rate60
# 本次图脚本默认三轮命名为c10/c30/c50-confirm，另一个结果集需采用相同名称。
/tmp/lottery-perf-charts/bin/python doc/performance/plots.py doc/performance/results/20261012-final
```

本次实际MySQL版本8.0.46、Redis7.2.16、ZooKeeper3.8.7，Compose已固定这些版本标签。最初拉取时使用大版本标签，`environment.json`保留当时容器名、标签、实际版本与资源限制。

JMeter安装包使用Apache的5.6.3发行包；本机Apache archive下载超时后，改从华为Apache镜像获取同版本包，展开到`/tmp/apache-jmeter-5.6.3`。`--jmeter`或`JMETER_BIN`可以指定自己安装的同版本工具。默认目录是本次实际安装位置，不属于项目业务配置。

## 仓库交付范围

本次[结果索引](results/20261012-final/README.md)保留报告、PNG/SVG曲线、环境与每轮参数、业务/资源/SQL汇总、一致性审计及稳定性判定。源代码仓库不存放逐请求JTL、全量订单/账本导出、进程列表、运行日志、临时编译目录和33.74MiB原始压缩包；这些文件仍保留在执行机器本地，`.gitignore`不会删除它们。完整原始包文件名为`raw-results-20261012.zip`。

从仓库克隆后，可以按本文重新运行得到同结构的完整证据。若只使用仓库中的汇总文件，可复核报告数字与判定，不能重新计算首次测试的逐请求分位数或逐ID审计；后两者需要本地原始包。
