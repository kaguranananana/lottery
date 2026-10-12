# 初始化 Lottery 的 Trellis 与开发规范

## 目标与范围

使用本机已安装 Trellis 的标准初始化流程创建项目上下文、任务管理和源码规范；保留已有 Git 历史与业务功能。用户已明确授权本次初始化。

## 架构依据

读取根与各模块 Maven 文件、README、architecture、相关 Java 源码、Mapper XML、SQL 和现有测试。项目为五模块 Java 8 / Spring Boot 后端，统一 backend 规范，无前端代码。领域定义接口，基础设施承担持久化、路由与 Redis，应用承担编排与异步消息。

## 交付

标准 native 工作流、Codex 项目技能与 hooks、开发者 anontokyo 工作区、15 份项目规范、操作说明，以及项目 AGENTS 约定。配置关闭 session 自动提交并使用 Codex inline。工具自带脚本原样使用。

## 验收

- [x] 本机 CLI 版本与 init 参数已检查，使用标准 trellis init 执行。
- [x] 项目上下文与规范基于真实源码，包含具体文件、方法和边界。
- [x] 后端与指南索引覆盖全部文档，无不适用前端模板。
- [x] 中文注释、根因优先、最小修复与错误透明规则已持久化。
- [x] 文档本地链接与源码引用存在，规范无待填写占位内容。
- [x] 上下文读取与任务清单校验通过，任务归档并记录会话。
- [x] 原有已跟踪文件内容和 Git HEAD/提交数保持不变；无业务测试或重构。

具体分析与验证说明见 analysis.md；操作方式见 Trellis README。
