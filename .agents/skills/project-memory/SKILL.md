---
name: project-memory
description: "Lottery: read the current project state when resuming or switching agents, and record substantive development, debugging, or evaluation progress with verified results. No coding workflow gates."
---

# Lottery 项目记忆

恢复工作或切换 Agent 时：

- 阅读 `docs/PROJECT_STATUS.md`，再查看 `docs/DEVLOG.md` 最近的相关记录。
- 按当前改动需要阅读源码、测试及 `.trellis/spec/`；记录可能过时，重要现状以代码、测试和 Git 为准。
- 需要追溯历史时，再查阅 `.trellis/workspace/`、`.trellis/tasks/` 和 Trellis 会话记录技能。

完成有实质内容的开发、调试或评估后：

- 更新 `docs/PROJECT_STATUS.md`，如实记录已完成、进行中、受阻事项和下一步。
- 向 `docs/DEVLOG.md` 追加一条简短日期记录，说明相关文件、实际变化、实际执行的检查及结果、重要决定和后续事项。
- 只有长期有效的约定、决定或故障预防经验才写入 `.trellis/spec/`。
- 不为普通问答或无实质内容的小改动新增记录；不虚构验证结果或决定。

新工作使用项目状态和开发日志记录。已有 `.trellis/workspace/` 日志及 `.trellis/tasks/` 归档作为历史保留，不要求继续创建 Trellis task、PRD 或阶段审批。
