# Mac重启后的80 RPS基线复测

执行日期2026-10-13，Asia/Shanghai；业务源码基线b198ed6。最新结论见 [optimization-report.md](../../optimization-report.md)。本轮没有业务/索引优化或升压：180秒测量和同速率300秒确认均通过原稳定标准，开机以来Swap-in/out累计始终为0。最大稳定容量未测试。

## 新结果

| 轮次 | 目标/实际新抽奖RPS | 测量秒 | 全轮订单 | HTTP P99 ms | 异步P95上界 ms | 发奖采样积压峰值 | 排空秒 | 审计/稳定 |
|---|---|---:|---:|---:|---:|---:|---:|---|
| reboot-rate80 | 80 / 80.000 | 180 | 16,007 | 23 | 2,105 | 100 | 0.331 | 通过/通过 |
| reboot-rate80-confirm | 80 / 80.013 | 300 | 25,613 | 22 | 2,116 | 105 | 0.326 | 通过/通过 |

两轮30线程、20秒预热，100%中奖，10万用户fixture、每活动/每奖品100万库存，真实HTTP/Redis/分库分表MySQL/Outbox/Kafka/账本/本地模拟发奖。两活动分别740201/740202，使用同一组用户标识但独立活动次数及请求归属，前轮排空后才开始后轮。完整业务源与设置均保持原样。

`semantics`（740210）验证同/不同请求热点各1成功49 D006、读取超时后同ID只1单、D007原归属及take恢复，4个用户各1个新抽奖。`message-replay`真实重投库存/发奖各2条，事实未变、实际消费位点增长且Lag归零。两负载轮加功能验证共41,624个新订单/参与/账本。

初始全部历史MySQL为341,311订单/账本、381,439参与、2,424,009次数行，与b198ed6一致；最终为382,935订单/账本、423,063参与、2,624,013次数行。没有删库、删Volume、回填历史或重置位点。本轮逐ID/库存/消息验收与全局MySQL核对均通过，原五个其它实例始终保持重启后的停止状态。

## 资源与事件

四段约60秒资源观察：无Docker、Docker空闲、四容器空闲、应用空闲，均0 Swap-in/out。两负载轮及关闭后的原始累计计数仍0，覆盖准备和预热间隙。macOS内存压力原始读数1，原文和进程RSS保留。MySQL/Kafka原2 CPU配额未变，持久化1/1、原堆/池/消费并发与调度不变。

以下是工具事件，原始失败保留，不改写为压测失败或吞吐收益：

- Docker启动前未运行；容器/Volume均保留。初次挂载列表对照因数组顺序变化失败，逐目标核实实际名称/类型/目标一致。
- /tmp中的JMeter随重启丢失；官方Apache下载120秒超时，保留日志及退出28。使用上轮同源镜像恢复相同5.6.3至持久缓存。正式测量期间没有下载或Maven任务。
- `stop.py`实际拒绝了comm=java的专用PID；原guard错误要求/java后缀。真实进程旧guard失败，新文件名比较通过，仍校验PerfHarness主类。修复后原停止脚本正常关闭，非专用进程仍拒绝；不影响已完成的性能测量。

原始资源限制/端口/挂载、恢复健康轨迹、构建和依赖版本、原计划/实际命令、JTL、逐ID导出、锁等待/SQL/阶段/GC及应用日志均保留。采样积压峰值不是未采样瞬间的绝对峰值，异步时间仍为秒精度上界，嵌套阶段耗时不相加。

## 证据索引

| 文件/目录 | 内容 |
|---|---|
| host-before-docker、docker-idle、stack-idle、application-idle | 四阶段原始资源与增量；memory_pressure另在阶段快照保存 |
| docker-initial.json、recovery-identity.json、restore-health.json、stack-restored.json | 原容器/数据卷与限制核对、只恢复专用四实例的就绪过程 |
| dependency-status.json、baseline-plan.json、environment-gate.json | 工具版本、下载失败与恢复、预定80 RPS计划及环境决定 |
| mysql-before、initial-persistence-check.json、redis-before.json、kafka-topics-before.txt | 精确初始量、旧事实与参数、Redis持久化和既有Kafka主题 |
| 每轮 parameters/command/plan、summary/audit/stability、http-end/drain | 实际执行设置、窗口结果、全量审计、原九项标准及排空 |
| 每轮 resource-summary/stage-summary/sql-delta | 资源/主动换页/实际锁与数据库、生产回调、consumer/ack包含式计时 |
| semantics、message-replay | 请求幂等与真实重投的独立功能证据，不计为容量压力轮次 |
| final-global-audit.json、final-live.json、reboot-results.json | 本轮与历史总量明确分开，最终事实、收敛及两轮新结果 |
| application-stop.log、stop-reproduction.json、application-stop-fixed.log | 原停止失败、真实进程解析复现及修复后的正常停止 |
| ownership-and-shutdown.json、environment-after.json、host-after.json | 所有旧Volume保留、原五实例不变、MySQL退出0且未OOM、全程换页0 |

本轮修改后的stop.py只用于收尾；Java/Mapper/消息/库存/持久参数未改。没有重新运行业务单元或删除fixture的ManualIT，不能宣称它们本轮通过。旧轮次证据未覆盖，重启前停止结果仍为停止。

## 复现同速率

首先完成资源与原容器身份核对，只恢复当前专用旧栈，已有schema不执行init/clean/compose up。工具已恢复至持久缓存，以下从仓库根目录运行；session和活动ID必须尚未使用，替换为新值。

```bash
# 先检查Docker引擎状态，再按本轮记录检查原容器/Volume/限制。
docker desktop status
python3 doc/performance/preflight.py doc/performance/results/repeat-reboot/preflight
# 仅在此前核实身份、健康及参数的原四容器上执行。
docker start lottery-perf-mysql-1 lottery-perf-redis-1 lottery-perf-zookeeper-1 lottery-perf-kafka-1

# 固定JDK17；构建结束后再采资源和启动应用，测量不并行构建。
lottery_test_jdk=$(/usr/libexec/java_home -v 17)
env JAVA_HOME="$lottery_test_jdk" PATH="$lottery_test_jdk/bin:$PATH" bash doc/performance/build.sh
# 终端A：保持原应用前台，另一个终端完成就绪/资源检查后运行负载。
env JAVA_HOME="$lottery_test_jdk" PATH="$lottery_test_jdk/bin:$PATH" bash doc/performance/start.sh doc/performance/results/repeat-reboot
# 终端B：以下ID仅示例，确认尚未使用。只测80，不升压。
python3 doc/performance/run.py --session repeat-reboot --name reboot-rate80 --id 741201 --threads 30 --duration 200 --warmup 20 --target-rps 80 --jmeter "$HOME/.cache/lottery-perf/apache-jmeter-5.6.3/bin/jmeter"
python3 doc/performance/check_stability.py doc/performance/results/repeat-reboot/reboot-rate80
# 首轮通过且资源仍可信、全部排空才运行同速率300秒确认。
python3 doc/performance/run.py --session repeat-reboot --name reboot-rate80-confirm --id 741202 --threads 30 --duration 320 --warmup 20 --target-rps 80 --jmeter "$HOME/.cache/lottery-perf/apache-jmeter-5.6.3/bin/jmeter"
python3 doc/performance/diagnostics.py doc/performance/results/repeat-reboot/reboot-rate80 doc/performance/results/repeat-reboot/reboot-rate80-confirm
python3 doc/performance/stages.py doc/performance/results/repeat-reboot/reboot-rate80 doc/performance/results/repeat-reboot/reboot-rate80-confirm
python3 doc/performance/check_stability.py doc/performance/results/repeat-reboot/reboot-rate80-confirm
python3 doc/performance/stop.py doc/performance/results/repeat-reboot
python3 doc/performance/environment.py stop
```

仓库保存可复核参数/原计划/汇总/审计/资源与工具失败摘要。完整原始包为 `raw-results-20261013-reboot-baseline.zip`，日志、逐请求/逐ID、全部连续采样及SQL输出本地保留。使用仓库摘要可核对结论；重新计算窗口分位数/逐ID核对需要原始包或重新运行。不要把重启前后环境、历史体量和固定到达速率下的延迟差异包装成代码性能提升或生产最大容量。
