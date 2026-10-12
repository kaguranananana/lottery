# Lottery 实际性能压测复现

后续定向优化、当前字段修复及压测驱动数据耗尽修复见 [优化报告](optimization-report.md) 和 [本轮证据/复现步骤](results/20261012-optimization/README.md)。本文的首次测量数字和 NULL 字段描述对应原始测试版本，原始结果未覆盖。

测试启动器只额外装配本机控制面，`POST /api/v1/draw`、活动创建/启动、路由、库存、事务、Kafka、Outbox 和本地模拟发奖均执行仓库的生产实现。未接入真实第三方奖品渠道；XXL handler 由专用线程按一秒固定延迟调用，未验证 admin 调度、HA 或多实例。

## 环境与首次准备

需要 Docker Compose、JDK 17（运行现有 Java 8 目标字节码）、Maven、Python 3 和 JMeter 5.6.3。绘图额外需要 matplotlib。机器资源与软件版本以每次测试会话的 `environment.json` 为准。应用/JMeter 在宿主机，中间件在独立 Docker；MySQL 13329、Redis 17379、Kafka 29092，应用 18090，执行器 29999。端口均与现存 E2E 栈分开。

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

每次新建活动先调用真实创建服务，再执行 PASS→DOING（事务内 SETNX 预热），不直接修改状态为运行中。每轮默认预置10万用户，按实际路由策略进入唯一正确分库，并记录八分片分布；每用户10次机会，每次基准请求使用新用户、新 requestId。活动100万库存，五种模拟券每种100万库存、20%概率，100%中奖以覆盖较重的订单与异步主链路。预置用户并未绕过生产每次 `initializeCount` SQL。

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
python3 doc/performance/semantics.py --session my-run
# 活动库存竞争：最多100次新抽奖，其余应返回D004。
python3 doc/performance/run.py --session my-run --name finiteActivity --id 728001 --threads 50 --duration 15 --warmup 0 --stock 100 --users 100000 --jmeter /path/to/jmeter
# 奖品库存竞争：总共50份奖品，活动足够；售罄命中应为D001，不重新抽样。
python3 doc/performance/run.py --session my-run --name finiteAward --id 728002 --threads 30 --duration 15 --warmup 0 --stock 100000 --award-stock 10 --users 100000 --jmeter /path/to/jmeter
python3 doc/performance/analyze.py doc/performance/results/my-run/c10 doc/performance/results/my-run/c30 doc/performance/results/my-run/c50-confirm
```

无等待阶梯默认爬升5秒、预热15秒、测量60秒，总80秒。统计从首条采样开始，按请求**完成时间**落入稳定窗口计算 RPS，分位数使用最近秩。JMeter结束前最后不足一秒的窗口可能低估完成量；所有阶梯采用同一方法，不把提前开始但未完成的请求算吞吐。

`0000`为同步下单成功，`D001`为完成的未中奖抽奖，`D004/D006/D007`分别为售罄/处理中/原请求未完成。系统异常另计，HTTP 200 不等于业务成功。正常基准只允许0000/D001；幂等成功回放只算一个新操作。有限库存拒绝的高RPS不代表成功抽奖容量。

## 原始证据与采样

每轮保存参数、真实命令、JTL（业务码/request/take/order）、初始化值、3秒级 JVM/GC/Hikari/Tomcat/宿主进程/Docker采样、约9秒级 MySQL/Redis/Kafka/异步采样、SQL digest 前后值、停止入口后的积压、2秒级排空探针、全量参与/订单/账本ID和对账结果。采样本身会产生少量开销。个别容器/SQL采集耗时会拉长周期，原始 `collectionMs` 可核实实际间隔。

审计逐项检查用户与请求身份、take与order关联、次数消耗、各奖品库存与账本、全局重复订单、未消费参与、BLOCKED、库存/发奖最终状态、Kafka八分区位点和Lag。数据库时间戳精度为秒，HTTP→异步完成延迟报告一秒宽的上下界；没有伪造毫秒精度。

当前创建路径 `StrategyDetailVO.awardCount(Integer)` 与 `StrategyDetail.awardCount(String)` 不同，BeanUtils 不会复制，总库存列为NULL；剩余库存正常初始化。原始数据保留此缺陷，审计依据显式fixture数量与初始化剩余库存，不修改生产表掩盖缺陷。活动MySQL剩余库存当前不在参与链路扣减，运行期消耗以Redis活动库存与持久化参与记录解释，奖品库存最终结算到MySQL。

## 停止与安全清理

```bash
# 先停止专用应用并核对其PID身份，再停止独立栈，数据库与原始结果保留。
python3 doc/performance/stop.py doc/performance/results/my-run
python3 doc/performance/environment.py stop
# 需要从空库重跑时才删除专用Docker项目。标签和本机绑定端口必须匹配。
python3 doc/performance/environment.py clean
```

清理仅操作 `lottery-perf`，不会停止 `lottery-mq-e2e` 或其它实例。生成的结果目录不自动删除。再次复现使用新的 session 目录，并在专用栈清空后初始化。生产业务代码、数据库、Redis、Kafka和Git历史保持原样。

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
