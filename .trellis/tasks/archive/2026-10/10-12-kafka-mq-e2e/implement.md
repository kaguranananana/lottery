# 实施

1. 启动 Docker 与独立测试 compose，确认容器及服务健康。
2. 建立真实 Spring Boot 端到端测试和明确的 Future 延迟注入。
3. 验证正常链路、迟到成功/失败、重复消息、FAIL/SENT 补偿、发奖补偿、八分表路由及 Kafka ack。
4. 运行定向集成测试和 Maven 单测/构建，更新验证说明与 Trellis 记录。

测试容器保留运行供用户复验；不用现有持久卷，删除由 compose down 指定本测试工程执行。

四步均完成。实际命令、结果、故障根因和边界见 validation.md。
