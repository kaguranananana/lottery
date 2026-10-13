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
