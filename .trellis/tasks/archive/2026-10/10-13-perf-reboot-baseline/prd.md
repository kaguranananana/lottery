# 重启后资源验收与80 RPS基线复测

## Goal

依据 b198ed6 核实重启后换页与资源；保留现有数据恢复专用栈，仅复测80 RPS，不升压、不修改业务或其它实例

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
# 重启后资源验收与 80 RPS 基线复测

## 需求
依据 b198ed6 及 optimization-report.md 的环境停止条件，检查重启后的 macOS 内存压力、Swap-in/out 增量、Docker 与依赖占用。仅在环境可信时恢复已有专用栈并复测历史 80 RPS；不升压，不修改业务实现或稳定门槛。

## 约束与授权
用户已授权自主恢复所需环境与基线测试。不得删除现有 MySQL 数据、Docker Volume，不操作其它实例。之前日志/归档的未提交状态保留。已有数据复用，不执行 init/clean 或迁移。所有历史与本轮证据分开。

## 验收
- 保留无 Docker、Docker 就绪、专用栈/应用就绪及测试中的资源原始记录。
- 检查既有容器身份、限制、挂载及数据库精确初始数量，恢复原版本和配置。
- 无明显持续换页及不可控制干扰时，先 80 RPS、20 秒预热+180 秒测量；通过后同速率 300 秒确认，不尝试更高档位。
- 按既有脚本完成 HTTP、异步收敛、Kafka Lag、库存/次数/订单/账本/最终模拟发奖审计，严格复用 stability.md。
- 资源不可信时停止；业务或工具失败显式记录并定位，不改实现/阈值掩盖问题。
- 更新报告、保留原始包，说明本轮新结果、历史边界及主要占用/处理建议。
