# 初始化分析与验证记录

## 本机工具与标准方式

CLI 为 `@mindfoldhq/trellis 0.6.15`，Node 与 Python 3.14.7 可用。检查 `trellis init --help` 及本机安装包的 init、workflow、Codex configurator 实现，确认标准流程创建脚本、工作流、规范模板、任务、开发者工作区与 Codex 文件，沿用已有 Git 仓库。

执行 `trellis init --codex --no-monorepo --workflow native --user anontokyo --yes` 成功。`trellis platforms` 识别 Codex。native 使用内置模板，不依赖下载；查询工作流列表时远程 registry 索引未能访问，CLI 仍列出内置 native，本次未使用远程模板。

Codex CLI 初始化提示自动 hooks 依赖用户级配置和相应版本的 hook 审阅。没有修改用户级设置；使用标准上下文脚本与项目技能即可主动读取项目状态。

## 规范结构依据

五个 Maven 模块属于一个后端应用，使用统一 backend 索引和主题文档，不复制五套相同规范。保留标准后端数据库、日志、质量与错误处理主题，增加 HTTP、领域、应用消息和 Redis 主题。共享指南改为实际字段流转与项目能力复用；新增根索引与项目上下文，总计 15 份规范。

证据来自当前 Java、Mapper、SQL、测试和项目文档。本机没有 GitNexus CLI 或相关架构 MCP，使用直接源码分析完成。根 AGENTS 记录用户的中文注释、错误透明、根因优先及最小修复要求。

## 已记录的真实边界

- 参与记录与次数表仅分库，订单表才分表；V2 中的参与分表注释不符合当前 DAO 与基线。
- V2 的请求幂等与订单 take 关联 ALTER 为注释示例，不能认为执行文件即迁移了全部 schema。
- 请求级 take 查询捕获异常后继续原流程，这是既有偏差，不作为新代码规范。
- 库存结算失败写 FAIL 后仍被 Outbox 扫描，库存 SENT 没有超时扫描恢复。
- 发奖补偿发送回调写 COMPLETE，实际发奖事实应看 grant_state；真实发奖渠道仍为模拟实现。
- 库存 Redis key 不能按配置缓存的 miss 策略自动回填；快照缓存为短 TTL 最终一致。
- 单测为编排与 Guard mock；库存 ManualIT 为手写 SQL/Redis 骨架，不代表生产消费者全链路验证。

这些现状均写入对应主题，没有修复、重构或修改业务功能。

## 验证与 Git 边界

静态检查通过：15 份规范、164 个本地链接均存在，各目录索引覆盖全部主题，无待填写模板或未解析源码引用。`task.py validate 00-bootstrap-guidelines` 检查 implement/check 各 4 个实际上下文条目通过。

`get_developer.py` 返回 anontokyo；`get_context.py --mode packages` 识别单仓库 backend；`get_context.py --json` 正常读取身份、Git、任务和日志；按 Codex 提取 Phase 2.1 返回主会话执行流程，配置的 inline 模式有效。

`git diff --exit-code` 和 `git diff --cached --exit-code` 均无已有文件变更，HEAD 保持初始化前的提交。新增项仅为 `.trellis/`、`.agents/`、`.codex/`、根 AGENTS 与日志合并属性。关闭 session_auto_commit，归档与日志命令显式使用 no-commit。

未执行业务测试、SQL、应用启动或中间件操作。本次验证范围仅为初始化产物、文档与标准工具可用性。
