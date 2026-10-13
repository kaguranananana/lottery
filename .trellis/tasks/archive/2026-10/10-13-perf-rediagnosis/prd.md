# 真实链路性能复诊与资源门禁

## Goal

TBD.

## Requirements

- TBD

## Acceptance Criteria

- [ ] TBD

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.
# 新一轮真实链路性能复诊

## 需求与授权
以 main 的 34cd882 为基线，复核上一轮真实链路与证据，最多完成三个单问题优化。用户已明确授权自主调查、测试、必要修改及每项验证后独立提交；普通步骤不再询问。保留业务正确性、持久事务、幂等与既有稳定性阈值。

## 验收
- 先核实宿主 CPU/内存/主动换页、容器资源归属及初始数据规模；只操作本轮明确所属资源。
- 环境可信时先复测原版，逐步提高固定到达速率；复用真实 HTTP→Redis→分库分表 MySQL→Outbox→Kafka→账本→模拟发奖与既有脚本。
- 仅对复现且定位的问题建立测试、修改、相同条件 A/B、完整审计；每项验证后独立 commit。
- 环境不可比、无可靠瓶颈或风险明显时停止，保存停止证据与未执行项，不制造吞吐或优化宣称。
- 报告记录新发现、修改、结果、吞吐边界、遗留瓶颈及简历表达边界。

## 已知事实
主分支干净；旧专用四容器已停止，另外五容器仍运行，不属于本轮。宿主 16 GiB，初始 Swap 使用约 8.9 GiB，需用增量判断是否活跃。
