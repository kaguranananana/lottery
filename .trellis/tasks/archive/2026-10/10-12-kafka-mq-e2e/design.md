# 范围与方案

新增测试用 compose（MySQL 8、Redis 7.2、Kafka 2.8.1、ZooKeeper），不挂载部署目录数据；新增 MqMiddlewareE2eManualIT 和回调延迟测试生产者。测试生产者调用 KafkaProducer 原方法真实发送，仅将返回给调度任务的 Future 通知延后，以复现消费者先完成后的成功/失败通知。数据库和 Redis 不使用 Mock。Spring Boot 加载生产配置与组件；XXL-Job handler 使用显式任务上下文调用，暂不启动外部调度 admin。

测试表使用生产建表基线并补充现有字段迁移。用户路由覆盖八张表，库存账本与 strategy_detail 为全局库。以独立策略/用户/订单隔离用例。验证库行、库存、账本数量、Kafka 消费位点和 Redis marker。变更仅测试、测试配置与结果文档；运行环境异常先复现诊断，不调整生产业务掩盖问题。
