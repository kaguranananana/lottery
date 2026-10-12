# Kafka 消息状态修复的 Docker 端到端验证

> 本文保留此前验证记录；当前库存业务分歧已改为 BLOCKED=4，HTTP 和预热故障验证已补齐，最新契约与结果以[最终报告](final-correctness-e2e.md)为准。

2026-10-12 已在独立 Docker 栈验证生产异步链路，8 个集成用例全部通过。MySQL、Redis、Kafka、分片路由、库存 Repository、DAO/Mapper、生产者与消费者均真实运行，没有使用 Mockito 或中间件 Mock。

## 环境与执行范围

| 服务 | 实际版本 | 宿主机端口 |
| --- | --- | --- |
| MySQL | 8.0.46 | 13309 |
| Redis | 7.2.16 | 16379 |
| Kafka | 2.8.1，单 broker、单副本 | 19092 |
| ZooKeeper | 3.8 系列 | 仅测试容器网络 |

测试栈由 [docker-compose.yml](../lottery-interfaces/src/test/resources/mq-e2e/docker-compose.yml) 创建，不挂载 lottery-stack 的业务数据；Docker 与四个测试容器保留运行。Spring Boot 测试加载生产配置和组件，使用 [application-mq-e2e.yml](../lottery-interfaces/src/test/resources/application-mq-e2e.yml) 覆盖连接端口、监听器启动时刻与测试日志目录，生产 application.yml 不变。

数据库是全局 lottery 与 lottery_01/lottery_02，两库每库四张订单表。测试从生产 SQL 基线创建表，再执行现有库存 Outbox、全局账本迁移，并补入 Mapper 所需 take_id；只使用专用测试实例，不写现有业务库。每轮会重建这三个测试 schema，保留完成后的 fixture 供检查。

起点是已经持久化的中奖订单 Outbox：真实 Redis 预扣 → 生产 DAO 按 uId 路由插入订单 → 生产 XXL-Job handler 扫描 → 真实 Redis dispatch guard → KafkaProducer/Kafka broker → 真实 @KafkaListener → 全局库账本与库存本地事务 → 用户分片库存 COMPLETE → Kafka 发奖 → 模拟渠道处理器写真实 grant_state → mq_state COMPLETE → Kafka ack/提交位点。

XXL-Job handler 使用真实任务上下文直接调用，executor 也由 Spring 初始化；本次没有启动外部 XXL-Job admin 触发调度，没有覆盖 HTTP 抽奖入口。发奖渠道仍是项目已有本地模拟实现，不证明外部渠道副作用恰好一次。

## 自动化用例与结果

测试为 [MqMiddlewareE2eManualIT.java](../lottery-interfaces/src/test/java/com/anontokyo/lottery/test/manual/MqMiddlewareE2eManualIT.java)，完整执行 40.791 秒，8/8 通过，无失败、错误或跳过。

| 场景 | 实际断言 |
| --- | --- |
| 八张订单表连续异步链路 | 八个路由位置均写入并完成，MySQL/Redis 库存均从 100 降至 92，每订单一条账本 |
| 消费者先完成、成功通知迟到 | 两条真实消费者先写 COMPLETE，再执行库存与发奖成功通知，终态与 update_time 保持 |
| 消费者先完成、失败通知迟到 | broker 实际已接受消息，再注入迟到失败通知，不能覆盖两条 COMPLETE |
| 多次重试回调乱序 | 真实 Redis marker 过期后再次发送，最新成功先到、旧失败后到、最旧成功在消费完成后到，仍只结算一次 |
| 库存 SENT 超时补偿 | fixture 时间设为 11 分钟前，生产扫描真实重投并最终完成 |
| 发奖 FAIL/SENT/历史错误 COMPLETE | 真实库存结算后扫描补偿，各订单实际发奖并完成，两条链路不会永久挂起 |
| 重复 Kafka 消息 | 重复库存及发奖消息真实进入 broker，库存不再扣减，账本唯一，grant_date 不再更新 |
| 真实 broker 不可用与恢复 | 停止 Kafka 容器，真实 Future 发生 KafkaProducerException/TimeoutException，订单落 FAIL 并清理 marker；恢复健康后生产补偿再次投递，最终完成 |

迟到回调的控制只在测试中装饰生产 KafkaProducer：序列化和发送均委托原生产对象，实际 broker Future 已成功后才放行通知。这里的“迟到失败通知”是显式故障注入，不能描述为 broker 真实发送失败；后一项容器停机用例单独证明真实发送失败与恢复。DAO、数据库、Redis、库存结算、KafkaTemplate 和监听器都没有被替换为 Mock。

暂停/恢复真实监听容器用于控制消费顺序，Redis marker 的过期使用真实 EXPIRE/TTL；时间窗口则通过老化独立测试行验证，未调整生产 SQL 的 10 分钟阈值。Kafka 测试 profile 将 delivery.timeout.ms 设为 5000、request.timeout.ms 为 1000、max.block.ms 为 5000，保持生产超时配置不变。

## 数据库与消费位点证据

最终数据库快照：

```text
订单总数             17
stock_mq_state=1      17
mq_state=1            17
grant_state=1         17
库存结算账本          17
测试策略               8
总库存扣减            17
```

八张订单表合计 17 个订单，每个只有一条结算账本；重复消息和补偿没有增加库存扣减。本轮消费者组为 lottery-e2e-stock-1791784213504 与 lottery-e2e-invoice-1791784213504，已提交的消息分区 CURRENT-OFFSET 均等于 LOG-END-OFFSET，LAG 全部为 0。没有消息的分区不产生 offset 记录；本测试沿用八分区 topic 配置。

测试通过 AdminClient 检查真实 RecordMetadata 对应的分区及 offset，要求消费者提交位置越过每条已接受消息；真实停机失败的 Future 单独验证原始 cause，不纳入已接受消息的 ack 目标。测试结束关闭 Spring 上下文，消费组没有活动成员，但已提交位点仍可检查。

## 复验与清理

从仓库根目录运行；必须使用此专用测试 compose，测试会重建 13309 上的三个 fixture schema。

```bash
# 首次运行会拉取镜像；--wait 等待真实容器健康检查。
docker compose -f lottery-interfaces/src/test/resources/mq-e2e/docker-compose.yml up -d --wait

# 仅运行真实中间件端到端用例，使用项目支持的 Java 8。
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn test \
  -Dtest=MqMiddlewareE2eManualIT \
  -Dsurefire.failIfNoSpecifiedTests=false

# 默认门禁与 Maven 构建不需要启动本组外部服务。
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn clean test
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn package

# 仅清理本组测试容器及其匿名卷；当前验证后未执行此命令。
docker compose -f lottery-interfaces/src/test/resources/mq-e2e/docker-compose.yml down -v
```

本次默认单测 33/33 通过，Maven package 全模块成功，生成 Lottery.war。根 pom 保持 ManualIT 默认排除，本次未修改 Maven 依赖或生产业务代码。

## 本次定位的运行问题

首次 fixture 将生产列注释中的分号误当 SQL 结束符；改用 Spring ScriptUtils 解析完整 SQL 引号和注释，没有修改基线表定义或断言。

首次 Kafka 停机恢复失败：默认 Docker 10 秒停止宽限期提前强杀 Kafka JVM，旧 ZooKeeper broker 会话尚未释放，立即启动报 NodeExists。测试 compose 设为 40 秒停止宽限期后，日志确认 ZooKeeper session closed、Kafka shut down completed，实际重启与补偿均通过。没有加入自动重试或通过重建 broker 数据掩盖该失败。

本次仅验证单机开发中间件和已有模拟渠道。多 broker 主从切换、真实外部发奖、HTTP 参与抽奖入口、外部 XXL-Job admin 调度触发均不在本次结论内。原修复的部署要求仍适用：旧无条件 UPDATE 的实例不能混跑后被当作已受 CAS 保护。
