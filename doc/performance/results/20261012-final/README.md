# 2026-10-12 压测证据索引

[完整报告](../../report.md)记录测试口径、结果、瓶颈和容量限制，[复现说明](../../README.md)提供实际环境与执行步骤。

| 证据 | 内容 |
|---|---|
| [environment.json](environment.json) | 测试机器、JDK/Maven、Docker资源、软件版本与源码修订 |
| [run-index.json](run-index.json) | 正式阶梯、定速校准、长时间复核、库存和幂等场景，以及排除原因 |
| [aggregate.json](aggregate.json) | 正式正常/定速164868个中奖订单；最大稳定容量未确认 |
| [final-global-audit.json](final-global-audit.json) | 全局185754个唯一订单/账本及本地发奖调用，最终Lag与未完成状态 |
| [host-memory-summary.json](host-memory-summary.json) | 宿主机主动换页计数与原始vm_stat摘录，未收录进程列表 |
| [test-summary.json](test-summary.json)、[quality-check.json](quality-check.json) | 本轮34个默认单测及编译/配置检查，排除旧ManualIT报告 |
| [performance-curves.png](performance-curves.png)、[sustained-backlog.png](sustained-backlog.png) | 并发变化与持续测试积压曲线，另有SVG |

各场景目录保留parameters/setup、实际JMX计划、summary、audit、http-end、drain等小型证据；定速场景另有stability，资源与SQL汇总按本次实际采集情况保存。`c50`为计划50但实际49线程的无效轮次，正式50并发使用`c50-confirm`。`semantics`是发送请求前失败的准备记录，完整正确性结果使用`semantics-confirm`。

库存/幂等以0000、D001与D004/D006/D007的真实业务含义统计。报告中的328.9RPS是本地短时同步抽奖吞吐；60/70/80/90持续复核没有全部通过预设条件，不能把峰值宣称为生产稳定容量。

完整JTL、采样JSONL、运行日志、逐ID导出和33.74MiB原始包`raw-results-20261012.zip`保留在执行机器本地，未提交Git。仓库汇总可以审核报告与判定；首次测试的分位数重算和逐ID重审需要原始包。重新执行脚本会生成这些文件。
