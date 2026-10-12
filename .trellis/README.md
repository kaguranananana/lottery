# Lottery 的 Trellis 使用说明

本项目于 2026-10-12 使用本机已安装的 `@mindfoldhq/trellis 0.6.15` 标准 CLI 初始化：

```bash
# 在 Lottery 仓库根目录初始化 Codex 接入与本机内置 native 工作流。
trellis init --codex --no-monorepo --workflow native --user anontokyo --yes
```

五个 Maven 模块共同提供一个后端服务，使用统一的 backend 规范，因此未启用每包独立规范模式。项目入口是 [规范索引](./spec/index.md)和[项目上下文](./spec/project-context.md)。

## 已创建的内容

- [workflow.md](./workflow.md)：CLI 内置 native 开发流程与分阶段指引。
- [config.yaml](./config.yaml)：项目配置，`session_auto_commit: false`，Codex 使用 `dispatch_mode: inline`。
- `scripts/`：标准身份、上下文、任务与会话管理脚本，沿用工具实现。
- `spec/`：15 份基于当前源码的中文规范，包括根索引、上下文、后端与跨层指南。
- `workspace/anontokyo/`：开发者日志与索引；本地身份文件由工具创建并忽略。
- `tasks/archive/2026-10/00-bootstrap-guidelines/`：本次初始化的需求、分析与完成记录。
- `agents/`：标准运行时角色定义，供后续确有需要的任务使用。
- 根目录 [AGENTS.md](../AGENTS.md)：标准 Trellis 入口及用户的中文注释、根因优先和最小修复约定。
- `.agents/skills/` 与 `.codex/`：标准项目技能、配置、hooks 与角色定义。
- 根目录 [.gitattributes](../.gitattributes)：追加型日志的合并规则。

## 在 Codex 中使用

新会话调用 `$trellis-start` 读取身份、Git 状态、任务与规范。新需求可调用 `$trellis-brainstorm` 写需求与方案，实施前调用 `$trellis-before-dev`；开发后按改动调用 `$trellis-check`，经验沉淀调用 `$trellis-update-spec`，继续任务调用 `$trellis-continue`。

本项目选择主会话直接执行，不要求启动子代理。技能中关于提交或审批的通用流程服从用户已经明确给出的授权；当前配置下日志与归档不自动暂存或提交 Git。

CLI 提示 Codex 自动 hooks 需要用户级 `features.hooks = true`，适用版本还需 `/hooks` 审阅。该前提由工具说明，本次未修改用户级配置。无论自动 hooks 是否启用，都可使用 `$trellis-start` 与下列标准脚本主动读取上下文。

## 通过脚本管理任务

以下命令在仓库根目录运行。以「修复抽奖请求幂等」任务为操作示例；这里只给出用法，未创建该业务任务。

```bash
# 阅读当前身份、活动任务和 Git 工作区。
python3 .trellis/scripts/get_context.py

# 查询规范层次；当前只有 backend 与共享指南。
python3 .trellis/scripts/get_context.py --mode packages

# 创建需求任务，返回实际目录名；先完成该目录中的 prd.md。
python3 .trellis/scripts/task.py create "修复抽奖请求幂等" --slug draw-idempotency --no-start

# 查看任务列表，获得下一步要使用的实际任务名。
python3 .trellis/scripts/task.py list
```

任务目录按创建日期命名。假设上述任务在 10 月 12 日创建，其名为 `10-12-draw-idempotency`；其它日期使用 `create` 返回的实际名称。轻量任务用 PRD 记录需求与验收，复杂任务再编写 design.md 和 implement.md。

```bash
# 在 AI 会话内开始已明确需求的任务，并持久化本会话任务指针。
python3 .trellis/scripts/task.py start 10-12-draw-idempotency

# 查询当前任务和任务指针来源。
python3 .trellis/scripts/task.py current --source

# 完成开发与相关验证后，清除本会话任务指针。
python3 .trellis/scripts/task.py finish

# 完成的任务归档，显式保持 Git 提交由开发者负责。
python3 .trellis/scripts/task.py archive 10-12-draw-idempotency --no-commit

# 将本次进度写入开发者日志，不自动提交。
python3 .trellis/scripts/add_session.py --title "抽奖幂等修复" --summary "记录实际改动与验证结果" --no-commit
```

`task.py start` 的任务指针依赖会话标识。普通终端若没有 AI 会话标识，可先执行 `export TRELLIS_CONTEXT_ID=lottery-terminal`，同一终端后续命令保持此值；不同并行终端使用不同标识。该标识只影响本地 `.runtime/` 会话状态，不进入业务数据。

需要为任务记录读取材料时可使用 `task.py add-context` 与 `task.py validate`，以脚本 `--help` 的真实参数为准。单主会话仍直接读取相关规范，JSONL 可用于保留明确的上下文清单。

## 本次验证范围

检查规范索引与本地链接、残留模板、工具版本及平台配置，读取标准上下文，校验 bootstrap 上下文并记录归档。没有修改 Maven、Java、Mapper、SQL、运行配置或已有 README，也没有执行业务测试或产生 Git 提交。源码现存偏差已写入相关规范，后续以独立任务处理。
