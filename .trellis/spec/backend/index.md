# 后端开发规范

适用于 Lottery 的全部 Java 模块、MyBatis XML 和 SQL 变更。先读[项目上下文](../project-context.md)，根据任务选择下列文档。

| 文档 | 涉及的改动 |
| --- | --- |
| [目录与依赖](./directory-structure.md) | 新增类、职责、模块依赖、公共类型 |
| [HTTP 契约](./http-contract.md) | Controller、请求校验、响应字段、业务码 |
| [应用编排与消息](./application-guidelines.md) | 抽奖用例、Kafka、Outbox、XXL-Job、消息 CAS 与超时补偿 |
| [领域规则](./domain-guidelines.md) | 参与资格、抽样概率、状态机、订单、发奖 |
| [数据库与路由](./database-guidelines.md) | DAO/PO/XML、事务、分片、约束与迁移 |
| [Redis 与缓存](./redis-guidelines.md) | 库存、锁、配置缓存、TTL 与预热 |
| [错误处理](./error-handling.md) | 业务拒绝、异常传播、补偿、现有偏差 |
| [日志](./logging-guidelines.md) | 请求关联、库存告警、消息与任务日志 |
| [质量与验证](./quality-guidelines.md) | Java 8、中文注释、测试选择、交付边界 |

## Pre-Development Checklist

- 明确任务改变的业务行为、接口和字段，记录在任务 PRD。
- 沿现有调用链找到入口、Repository、Mapper 和相关测试，确认职责及事务边界。
- 涉及幂等、库存或消息状态时，阅读已知边界，不能只照抄历史注释。

## Quality Check

- 保留业务码、概率与幂等语义；字段从 HTTP 到落库或消息保持一致。
- 生成代码使用简体中文注释，关键逻辑逐行解释；遵循根因优先、最小修复。
- 按实际改动完成相关验证并记录结果；仅文档改动检查文档与 Trellis 工具。
