# RocketMQ 事务消息重构

## 事务与恢复

- TX1 在 MySQL 原子扣个人次数并创建参与。命中奖品后，生产者先等待库存 Half Message 确认，再执行 TX2。TX2 用参与 CAS 取得行锁，调用 Redis Lua 预扣并同时登记可恢复记录，最后写订单。真实售罄提交参与消费并回滚 Half Message。Broker 发送失败时原参与继续可用。
- 有已提交的订单就回查为 Commit。无订单且仍在 30 秒执行窗口内，或到期后参与行锁仍被占用，回查为 Unknown。到期后获取同一把行锁并重新读取订单，确认无订单才 Rollback。TX2 获锁后检查数据库时间，避免迟到的回调在回滚决定之后另建订单。生产者启动时发现库存 Topic 路由并立即向 Broker 注册，以便重启后主动接收回查。 [RocketMQ 事务消息文档](https://rocketmq.apache.org/docs/featureBehavior/04transactionmessage/)
- Redis 待对账记录按截止时间扫描。同一参与持有行锁时复核订单，已提交则清记录，已回滚则归还一次预扣。原请求重试也检查该参与的遗留预扣。运行使用同一 Redis 实例存放库存和对账记录，Redis 数据需要持久化。运行中库存 Key 丢失会显式报错，余额需要人工对账。
- TX3 在同一个 MySQL 事务里写唯一结算账本并条件扣库存。库存完成的订单 CAS 同时登记发奖义务。普通发奖消息在本地模拟处理器完成 CAS 后确认，重复消息保持原发放时间。真实外部渠道上线需要以订单号做渠道幂等或查单。

## Outbox 变化与代价

库存正常路径删除了提交后事件和应用首发队列。库存与发奖都移除了发送回调、SENT/FAIL 状态、投递代数和发送结果 SQL。剩余补偿只扫描到期未完成义务，领取时 CAS 移动核对时间。

库存保留 10 分钟核对入口，覆盖 Half Message 超出 Broker 回查窗口或库存消费耗尽重投次数。发奖保留首发和持久化补偿，覆盖库存完成后到发奖消息发布之间的进程退出、发送失败和消费失败。库存分歧进入 BLOCKED。人工确认并修正奖池库存后，将目标订单从 4 改回 0 且设置 stock_retry_at 为当前时间，再由库存核对任务投递。

中奖请求增加同步 Broker 往返，并在持有参与锁时执行 Redis Lua。单 Broker 开启同步刷盘，供故障及容量验证。Broker 停机时命中奖品的请求进入可重试错误，保留已登记的参与和原 requestId。生产多副本和真实外部渠道尚未验证。实验分支直接使用新库，无旧测试数据迁移。运行时使用 RocketMQ Client 5.3.4，继续兼容 Java 8 和 Spring Boot 2.3.5。

## 故障验收

Java 8 打包通过 45 项默认单测及 56 项独立集成测试，均无失败或跳过。真实 HTTP 套件 30 项，另外覆盖请求归属、SQL CAS、补偿索引、Redis 预扣和准入。故障脚本执行两次 SIGKILL。 [测试汇总](validation/rocketmq-20261114/verification.json) · [进程退出记录](validation/rocketmq-20261114/process-crash.jsonl)

| 场景 | 实测观察 |
|---|---|
| 正常、售罄、原请求重放 | 真实 HTTP 集成覆盖，次数、参与、订单、库存和发奖状态按预期收口 |
| TX2 订单 INSERT 回滚 | 参与仍未消费，Redis 回补，无中奖订单，原请求后续成功 |
| Broker 停机、Half Message 发送失败 | Redis 预扣未发生。Broker 恢复后同一参与继续，额度只扣一次 |
| 发奖首发失败 | 库存仍完成，订单上的义务保留。调用真实补偿任务后通过 Broker 完成模拟发奖 |
| 重复消息、SQL 失败、BLOCKED | 重复记录由账本和 CAS 去重。SQL 修复后原消息可继续；BLOCKED 需显式解除 |
| TX2 提交前 SIGKILL | 参与锁存在时回查持续 Unknown，即使超过本地截止时间。重启就绪后约 1.97 秒查得回滚并回补，重试复用原参与 |
| TX2 提交后、消息 Commit 前 SIGKILL | 首次启动实验定位到生产者未发现路由导致收不到回查。修复并复演后，重启就绪约 1.83 秒回查 Commit 并完成原订单结算和发奖 |

## 复现

以下命令从仓库根目录执行，拥有独立 MySQL、Redis、NameServer 及 Broker。Compose 仅绑定本机端口，测试会重建其专用库。

```bash
docker compose -f lottery-interfaces/src/test/resources/mq-e2e/docker-compose.yml up -d --wait
bash lottery-interfaces/src/test/resources/mq-e2e/create-topics.sh lottery-rocketmq-e2e-broker-1 10911
mvn clean package
mvn -pl lottery-interfaces -am -Dtest=FinalBusinessE2eManualIT,RedisStockFailureManualIT,DrawAdmissionRedisManualIT \
  -Dlottery.it.redis.port=16379 -Dsurefire.failIfNoSpecifiedTests=false test
```

进程退出复用独立压测启动器。在应用 18190 空闲、压测库已初始化且 Topic 已创建时执行。

```bash
python3 doc/performance/rocketmq_process_crash.py /tmp/lottery-crash-new 810013 810014
```

JMeter 启动、参数和对账步骤见 [压测指南](../doc/performance/README.md)。

## 压测口径

Apple M4 本机运行 JMeter 5.6.3 和 JDK 17 压测启动器。Redis 7.2.16、MySQL 8.0.46 和 RocketMQ 5.3.4 使用 Docker。MySQL 2 CPU / 2 GiB，事务日志和 Binlog 刷盘参数为 1/1，组提交延迟为 0。Broker 为原生 ARM64 单实例、2 CPU / 1 GiB、同步刷盘。每档使用 65,000 个预置用户、五种等概率奖品、100% 中奖、72 线程、15 秒预热。仅容量测量通过已有开关关闭准入。80 RPS 轮后段与隔离故障复测重叠，120 RPS 轮开头仍有短时重叠。应用构建验证使用 Java 8。

Kafka 的较早 V8 对照使用相同的 JMeter 大版本、线程数、奖池结构、用户数、MySQL 版本与 CPU/内存限制。它在 40/80 RPS 使用短窗口，120/150 使用较长窗口并出现积压。后续 Kafka V8 两轮 150 RPS 另有稳定通过记录，库存 P95 上界为 59/893 ms，排空 1.18/8.83 秒。两代 Broker 的刷盘、指令架构、位点提交和宿主背景负载不同。两组实验能比较各自已观察到的延迟和稳定性范围，无法将差异单独归因到 Broker。 [早期 V8 对照](activity-prize-pool.md#2026-11-13-实测) · [后续 V8 回写实验](../doc/performance/v8-outbox-write-report.md)

库存和发奖延迟采用订单 INSERT 时间到实际完成时间作为上界。Broker Lag 由实际队列尾部减去 Broker 保存的消费位点，包含已经处理但位点尚未持久化的消息。排空同时要求业务无待完成订单且两个消费组 Lag 归零，因此包含位点提交周期与两秒采样粒度。

## JMeter 实测

| 目标 RPS | 测量秒数 | 实际抽奖/s | HTTP P95/P99 ms | 库存 P95/P99 上界 ms | 发奖 P95/P99 上界 ms | 采样峰值 Lag 库存/发奖 | 排空 s | 原稳定性门槛 |
|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 40 | 190 | 39.98 | 12/108 | 8/236 | 12/311 | 16/9 | 4.39 | 通过 |
| 80 | 190 | 79.99 | 72/299 | 195/685 | 213/709 | 21/10 | 4.46 | 通过 |
| 120 | 190 | 119.99 | 196/417 | 550/812 | 572/838 | 123/11 | 4.49 | 通过 |
| 150 | 300 | 149.83 | 338/538 | 907/1,163 | 926/1,182 | 180/12 | 4.50 | 通过 |

四档合计 96,539 笔订单，HTTP 和系统错误均为零。逐笔匹配参与、订单、额度、结算账本和奖品余额。最终 Redis/MySQL 库存一致，孤立预扣、BLOCKED、待结算、待发奖和两组 Lag 均为零。四轮宿主 Swap-in/out 增量为零。150 RPS 测量窗内库存和发奖分别完成 149.56 和 149.55 笔/秒，待发奖采样峰值 177 单，首尾平均增长 50.43 单。 [四档汇总](validation/rocketmq-20261114/load-summary.json) · [150 RPS 对账](validation/rocketmq-20261114/rate150/audit.json)

40/80/120/150 RPS 全轮 InnoDB 更新行分别为 41,290 / 82,075 / 123,185 / 236,145，恰为每单 5 行。典型路径对应扣额度、消费参与、扣库存、确认库存、确认发奖。SQL digest 中显式 COMMIT 调用约为每单 3 次，两个发送结果 UPDATE 已消失。Digest 在活动预置完成后采集，语句计数受 Performance Schema 汇总边界影响。全局插入计数会混入引擎和同实例辅助活动，逐表写入以业务对账和 SQL digest 为准。 [150 RPS SQL 写入证据](validation/rocketmq-20261114/rate150/write-counts.json)

较早 Kafka V8 的 120/150 RPS 库存 P95 上界为 21.99/93.85 秒，当时两个持续档位未过门槛。后续 Kafka V8 两轮 150 RPS 已稳定通过，在其中较快的一轮，库存 P95 和 HTTP P99 都低于本轮 RocketMQ。RocketMQ 单轮 150 RPS 持续 300 秒通过，HTTP P99 为 538 ms，库存 P95 上界 907 ms。本轮结果足以确认目标负载下收敛，尚未复跑同窗 Kafka 基线或更高档位。

完整 JTL、SQL 前后快照与资源采样保存在本工作区的新建结果目录。版本库保存四档汇总、参数、SQL 证据、对账和稳定性判定。
