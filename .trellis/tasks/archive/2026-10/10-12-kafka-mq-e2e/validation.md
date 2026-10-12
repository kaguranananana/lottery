# 验证完成

- Docker Desktop 已启动，独立测试栈四个容器保留运行，未挂载现有业务部署数据。
- MySQL 8.0.46、Redis 7.2.16、Kafka 2.8.1、ZooKeeper 3.8 系列。
- MqMiddlewareE2eManualIT：8/8 通过，40.791 秒，无失败、错误、跳过。
- 正常八分表、迟到成功/失败、真实 Redis TTL 与多次乱序重试、库存超时 SENT、发奖 FAIL/SENT/历史错误 COMPLETE、重复消息、真实 Kafka 停机/超时/恢复补偿均覆盖。
- 真正的两库八分表路由、生产 Mapper/Repository/事务、Redis guard、KafkaProducer/KafkaTemplate/@KafkaListener 与位点提交。没有中间件 Mock。
- 数据快照：订单 17；三种完成状态各 17；账本 17；测试策略 8；扣库存合计 17。两个消费组已提交的消息分区 LAG 均为 0。
- mvn clean test：33/33 通过；最后 mvn package 再执行 33 单测通过，全模块构建成功。
- 真实 broker 停机 Future 为 KafkaProducerException 包装 Kafka TimeoutException。迟到失败通知注入单独标明 broker 实际已接受消息，不混为真实发送失败。
- 未覆盖 HTTP 抽奖、外部 XXL-Job admin 调度、真实外部渠道、多 broker 切换。测试从持久化订单 Outbox 起，当前发奖处理器为模拟本地渠道。

## 失败定位

初次 fixture SQL 解析将注释内分号截断；使用 Spring ScriptUtils 正确解析生产 SQL。首轮完整测试 5 场景完成，其余因 Kafka 重启报 NodeExists 失败；Docker 默认 10 秒停止宽限期强杀进程导致旧 ZK 会话残留，测试 compose 改为40秒后日志确认完整关闭/会话释放。停机断言按 KafkaTemplate 实际包装 cause 逐层验证。未改生产业务规则，未削弱状态断言，未增加自动重试或 fallback。

## 交付

新增测试 Java、profile YAML、独立 compose、端到端文档，更新消息修复报告与 Trellis 规范。用户原改动保留，没有 Git 自动提交。详见 docs/kafka-middleware-e2e.md。
