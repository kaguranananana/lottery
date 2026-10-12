<!-- TRELLIS:START -->
# Trellis Instructions

These instructions are for AI assistants working in this project.

This project is managed by Trellis. The working knowledge you need lives under `.trellis/`:

- `.trellis/workflow.md` — development phases, when to create tasks, skill routing
- `.trellis/spec/` — package- and layer-scoped coding guidelines (read before writing code in a given layer)
- `.trellis/workspace/` — per-developer journals and session traces
- `.trellis/tasks/` — active and archived tasks (PRDs, research, jsonl context)

If a Trellis command is available on your platform (e.g. `/trellis:finish-work`, `/trellis:continue`), prefer it over manual steps. Not every platform exposes every command.

If you're using Codex or another agent-capable tool, additional project-scoped helpers may live in:
- `.agents/skills/` — reusable Trellis skills
- `.codex/agents/` — optional custom subagents

Managed by Trellis. Edits outside this block are preserved; edits inside may be overwritten by a future `trellis update`.

<!-- TRELLIS:END -->

# Lottery 项目约定

先阅读 `.trellis/spec/index.md` 和项目上下文，再按改动范围阅读后端规范。开发任务按 `.trellis/workflow.md` 管理；操作示例见 `.trellis/README.md`。本项目使用 native 工作流，Codex 直接在主会话执行，任务日志与归档不自动提交 Git。

生成的代码须加入简体中文注释，关键逻辑须逐行解释。

遇到异常、失败或测试不通过时，优先复现问题、定位根因并修复主链路。不要用 fallback、降级、兼容分支、静默吞错、临时补丁或针对单个样例的特殊规则掩盖问题。错误须显式暴露，并保留必要的日志或 Trace。只有需求、接口契约或真实失败案例证明必要时，才新增重试、fallback、降级或兼容逻辑，并说明触发条件、边界和测试覆盖。测试失败先检查实现，避免为了通过测试而修改测试或增加特殊分支。

遵循根因优先、错误透明、最小修复、避免无依据的复杂度。禁止过度防御，不添加哈希或 SHA256 校验，不为基本不可能出现的情况反复增加防御，不机械堆叠评分规则。

引用代码文件时默认显示文件名；同名文件使用最短可区分的相对路径。不要在可见文本中显示绝对路径。

保留现有业务接口、数据语义和 Git 历史；初始化规范中记录的现存问题须在独立开发任务内处理。现存降级或吞错行为只作为源码现状说明，不作为新增代码的推荐模式。
