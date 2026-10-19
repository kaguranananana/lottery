# Journal - anontokyo (Part 1)

> AI development session journal
> Started: 2026-10-12

---



## Session 1: 初始化 Lottery Trellis 与项目规范

**Date**: 2026-10-12
**Task**: 初始化 Lottery Trellis 与项目规范
**Branch**: `main`

### Summary

使用 Trellis 0.6.15 标准初始化；完成 15 份源码规范、操作说明和 bootstrap 归档；保留业务代码与 Git 历史。

### Main Changes

- 标准 native 工作流与 Codex 项目接入，关闭自动提交，使用主会话执行。
- 整理 HTTP、编排、领域、数据库、Redis、日志、错误处理及跨层规范，并记录真实源码边界。

### Git Commits

(No commits - planning session)

### Testing

- [OK] 15 份规范、164 个本地链接及索引检查通过，bootstrap implement/check 上下文清单各 4 项校验通过。
- [OK] 身份、backend 发现、JSON 上下文与 Codex inline 阶段读取正常；已有文件 diff 和暂存区 diff 均为空。

### Status

[OK] **Completed**

### Next Steps

- 通过 trellis-start 读取上下文，按需求创建新任务、记录 PRD、开发与验证后归档。


## Session 2: 修复抽奖请求归属与未完成恢复

**Date**: 2026-10-12
**Task**: 修复抽奖请求归属与未完成恢复
**Branch**: `main`

### Summary

同请求复用/回放，异请求D007恢复原ID；数据库查询失败中断，DB唯一键及条件扣次数；默认26与真实MySQL集成9通过，交付V3迁移，业务库未执行。改动未提交Git。

### Git Commits

(No commits - planning session)

### Status

[OK] **Completed**


## Session 3: Kafka 消息状态 CAS 修复

**Date**: 2026-10-12
**Task**: Kafka 消息状态 CAS 修复
**Branch**: `main`

### Summary

修复 stock_mq_state/mq_state 迟到回调回退；发送成功统一 SENT，消费者完成收口 COMPLETE，独立库存消费失败入口，超时及历史补偿恢复。定向 20/20（13 真实 MySQL + 7 应用单测）、默认 33/33、Maven package 成功。真实 MySQL 9.6 执行生产 Mapper/Repository，20轮×12并发；Kafka/Redis及库存结算 Mock，未验证八分表路由和真实渠道。用户已有改动保留，未自动提交。

### Git Commits

(No commits - planning session)

### Status

[OK] **Completed**


## Session 4: Kafka 状态修复的真实 Docker 端到端验证

**Date**: 2026-10-12
**Task**: Kafka 状态修复的真实 Docker 端到端验证
**Branch**: `main`

### Summary

启动 Docker 与独立 MySQL8.0.46、Redis7.2.16、Kafka2.8.1、ZooKeeper 测试栈；新增真实生产组件 E2E ManualIT，8/8通过。验证八分表、库存事务、真实guard/消费位点、迟到回调、重复消息、FAIL/SENT/历史COMPLETE补偿、broker实际停机超时和恢复。17订单三状态均完成、17账本、扣库存17、消费LAG全0。默认33/33与Maven package通过。测试SQL解析与Kafka10秒停止宽限期问题已根因修复（40秒关闭ZK会话），未改生产业务规则。保留运行测试容器和原用户改动，无Git提交；HTTP/外部调度admin/真实外部发奖不在验证范围。

### Git Commits

(No commits - planning session)

### Status

[OK] **Completed**


## Session 5: 提交当前 Lottery 交付结果

**Date**: 2026-10-12
**Task**: 提交当前 Lottery 交付结果
**Branch**: `main`

### Summary

按用户明确要求提交推送当前结果。已拆分抽奖请求归属修复、Kafka 状态 CAS 与真实中间件端到端验证；Trellis 初始化、规范、任务归档与会话记录作为独立提交交付。最近验证默认33/33、定向20/20、真实Docker端到端8/8和Maven package均通过，远端main无新增提交。

### Git Commits

(No commits - planning session)

### Status

[OK] **Completed**


## Session 6: 最终正确性修复与真实 HTTP/中间件 E2E

**Date**: 2026-10-12
**Task**: 最终正确性修复与真实 HTTP/中间件 E2E
**Branch**: `main`

### Summary

修复活动创建、分页、库存 BLOCKED 对账与行锁内预热启动。Java 8 编译打包成功，34 单测、22 真实 SQL 集成、23 真实中间件 E2E 共79通过。四项修复前故障均复现；报告与证据索引已推送 main。外部奖品渠道和 XXL-Job Admin 未覆盖，生产高可用与跨系统崩溃窗口仍属边界。

### Git Commits

| Hash | Message |
|------|---------|
| `b398d32` | (see git log) |

### Status

[OK] **Completed**


## Session 7: Lottery 实际性能压测与环境瓶颈诊断

**Date**: 2026-10-12
**Task**: Lottery 实际性能压测与环境瓶颈诊断
**Branch**: `main`

### Summary

完成独立Docker真实HTTP并发/持续速率/幂等/有限库存测试；50并发328.9新抽奖RPS、P95/P99 362/574ms，正式164868中奖订单逐ID对账通过。持续60/70/80/90未全部通过预设标准，后段宿主机11.9GiB swap及主动换页，最大稳定容量未确认。34默认单测、Java/Python/shell/Compose检查通过；交付report.md、曲线、复现脚本及原始证据包。专用栈停止、原有5容器保留，未改生产业务/测试。

### Git Commits

(No commits - planning session)

### Status

[OK] **Completed**


## Session 8: 真实性能诊断与独立优化验证

**Date**: 2026-10-13
**Task**: 真实性能诊断与独立优化验证
**Branch**: `main`

### Summary

完成已有次数行跳过无变化 upsert、复用库存组合索引、Outbox 删除重复读取三个独立优化，另修复总库存 Integer 映射。54 项自动化测试通过，155557 订单/账本和187331参与一致；配对80RPS前后均通过，异步订单SELECT每单4→3，300秒候选复核通过。主动换页与数据增长限制下不认证最大容量或整体提升率。修复真实JMeter用户耗尽失效；专用MySQL一秒关闭宽限恢复后事实一致，60秒正常退出。报告与30.82MiB原始包保留，专用栈停止，用户其他容器/原始结果未清理。

### Git Commits

| Hash | Message |
|------|---------|
| `170e8f5` | (see git log) |
| `5a8c3e6` | (see git log) |
| `e79d347` | (see git log) |
| `6be1963` | (see git log) |
| `90801f0` | (see git log) |

### Status

[OK] **Completed**


## Session 9: 资源受限的新一轮性能复诊与补偿扫描计划

**Date**: 2026-10-13
**Task**: 资源受限的新一轮性能复诊与补偿扫描计划
**Branch**: `codex/perf-rediagnosis-20261013`

### Summary

基于 main 34cd882；两段空闲仍换页，仅启动专用 MySQL 只读核对，96 次生产 SQL ANALYZE 确认补偿每轮扫描 341311 历史订单返回零。381439 参与与次数、341311 订单/账本及关联核对一致；原有五实例未改，MySQL 正常退出。环境停止，零业务优化，无新 RPS/真实链路结果；原始包本地保留，诊断工具与报告提交 b198ed6。

### Git Commits

| Hash | Message |
|------|---------|
| `b198ed6` | (see git log) |

### Status

[OK] **Completed**


## Session 10: 重启后零换页环境的80 RPS真实链路基线确认

**Date**: 2026-10-13
**Task**: 重启后零换页环境的80 RPS真实链路基线确认
**Branch**: `codex/perf-rediagnosis-20261013`

### Summary

依据b198ed6恢复原四容器与原Volume，不改其它五实例、不清数据。四阶段60秒和180/300秒80 RPS测量全程零Swap，两轮原标准全通过，P99 23/22ms，异步P95上界2.105/2.116s，约0.33s排空。幂等/超时/D007及真实Kafka重投通过；41624新增订单/参与/账本一致，最终全局382935订单/账本、423063参与。真实stop.py进程名解析失败最小修复并独立提交；所有原资源/数据保留，工具失败和7.45MiB原始包保留，未升压或业务优化。

### Git Commits

| Hash | Message |
|------|---------|
| `7b7de68` | (see git log) |
| `aa38c37` | (see git log) |

### Status

[OK] **Completed**


## Session 11: Lottery真实链路容量复核与JDBC会话缓存

**Date**: 2026-10-13
**Task**: Lottery真实链路容量复核与JDBC会话缓存
**Branch**: `codex/perf-rediagnosis-20261013`

### Summary

补偿空扫描同数据与反向验证；真实回滚门禁捕获8.0.28驱动回归并升级最小官方修复8.0.29；同驱动200RPS额外只读查询437907到0。150RPS两次连续300秒通过，150首轮及更高长测失败均保留，精确容量与资源贡献未认证。46项回归、真实幂等/超时/D007/Kafka重投/有限库存通过；全库961360订单与账本、1005414参与正确，新增578425订单。专用应用和四容器停止，原数据/22与61MiB原始包保留，原五容器不变。

### Main Changes

- 单列grant索引、会话参数与驱动修复分三工作提交，未改变池/并发/持久化/CAS/唯一账本。

### Git Commits

| Hash | Message |
|------|---------|
| `9310207` | (see git log) |
| `94b790e` | (see git log) |
| `076af9f` | (see git log) |

### Testing

- [OK] 默认35及最终真实SQL11共46通过；九个容量轮与真实HTTP/Kafka/有限库存审计。

### Status

[OK] **Completed**

### Next Steps

- 先控制I/O及容器资源状态再验证回调/重复读取候选，不将局部收益换算成整体吞吐倍数。


## Session 12: Lottery交互式源码图解

**Date**: 2026-10-14
**Task**: Lottery交互式源码图解
**Branch**: `main`

### Summary

交付docs/visual-guide：14幅Archify图、同步中奖14步/未中奖12步/异步15步、38文件源码摘录和字段事务速查；14图全部9/9及浏览器门禁通过，入口交互/版本资源/同页深链已复核；未修改业务代码。

### Main Changes

- 新增完整交互式架构、业务追踪、库存账本、模拟发奖与失败恢复图解

### Git Commits

| Hash | Message |
|------|---------|
| `f14b2ce` | docs: add interactive Lottery source guide |

### Testing

- [OK] 14图完整finalize通过；本地资源与41路径步骤定位通过；guide.js语法及CUA入口交互通过

### Status

[OK] **Completed**


## Session 13: Lottery工作区提交梳理

**Date**: 2026-10-19
**Task**: Lottery工作区提交梳理
**Branch**: `main`

### Summary

提交交互式源码图解交付；归档已完成的性能与图解任务并补齐前序日志提交号。清理 .DS_Store，业务源码与配置无改动。

### Git Commits

| Hash | Message |
|------|---------|
| `f14b2ce` | (see git log) |

### Status

[OK] **Completed**
