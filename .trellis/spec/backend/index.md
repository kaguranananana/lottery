# 后端开发规范

适用于 Lottery 的全部 Java 模块、MyBatis XML 和 SQL 变更。先读[项目上下文](../project-context.md)，根据任务选择下列文档。

| 文档 | 涉及的改动 |
| --- | --- |
| [目录与依赖](./directory-structure.md) | 新增类、职责、模块依赖、公共类型 |
| [HTTP 契约](./http-contract.md) | Controller、请求校验、响应字段、业务码 |
| [应用编排与消息](./application-guidelines.md) | 抽奖用例、Kafka、Outbox、Spring 定时任务、消息 CAS 与超时补偿 |
| [领域规则](./domain-guidelines.md) | 参与资格、抽样概率、状态机、订单、发奖 |
| [数据库与迁移](./database-guidelines.md) | DAO/PO/XML、事务、约束与迁移 |
| [Redis 与缓存](./redis-guidelines.md) | 库存、锁、配置缓存、TTL 与预热 |
| [错误处理](./error-handling.md) | 业务拒绝、异常传播、补偿、现有偏差 |
| [日志](./logging-guidelines.md) | 请求关联、库存告警、消息与任务日志 |
| [性能压测](./performance-testing.md) | 独立环境、真实HTTP/异步链路、指标与容量证据 |
| [独立性能优化](./performance-optimization.md) | 次数 upsert 锁、库存索引部署、Outbox 冗余读取及验证边界 |
| [质量与验证](./quality-guidelines.md) | Java 8、中文注释、测试选择、交付边界 |

## Pre-Development Checklist

- 明确本次改动影响的业务行为、接口和字段；按需在需求说明或简短记录中写下验收点，不强制创建任务 PRD。
- 沿现有调用链找到入口、Repository、Mapper 和相关测试，确认职责及事务边界。
- 涉及幂等、库存或消息状态时，阅读已知边界，不能只照抄历史注释。

## Quality Check

- 保留业务码、概率与幂等语义；字段从 HTTP 到落库或消息保持一致。
- 库存缺失、事务异常回补、配置提交后失效与显式 Snowflake 节点契约见[故障评估](../../../docs/failure-review-20261024.md)及对应主题规范。
- 生成代码使用简体中文注释，关键逻辑逐行解释；遵循根因优先、最小修复。
- 按实际改动完成相关验证并记录结果；纯文档或 Agent 配置改动检查引用和活动配置路由，不运行无关业务测试。
