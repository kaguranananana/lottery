# RocketMQ 事务消息重构

## 事务与恢复

- TX1 在 MySQL 原子扣个人次数并创建参与。命中奖品后，生产者先等待库存 Half Message 确认，再执行 TX2。TX2 用参与 CAS 取得行锁，调用 Redis Lua 预扣并同时登记可恢复记录，最后写订单。真实售罄提交参与消费并回滚 Half Message。Broker 发送失败时原参与继续可用。
- 有已提交的订单就回查为 Commit。无订单且仍在 30 秒执行窗口内，或到期后参与行锁仍被占用，回查为 Unknown。到期后获取同一把行锁并重新读取订单，确认无订单才 Rollback。TX2 获锁后检查数据库时间，避免迟到的回调在回滚决定之后另建订单。生产者启动时发现库存 Topic 路由并立即向 Broker 注册，以便重启后主动接收回查。 [RocketMQ 事务消息文档](https://rocketmq.apache.org/docs/featureBehavior/04transactionmessage/)
- Redis 待对账记录按截止时间扫描。同一参与持有行锁时复核订单，已提交则清记录，已回滚则归还一次预扣。原请求重试也检查该参与的遗留预扣。运行使用同一 Redis 实例存放库存和对账记录，Redis 数据需要持久化。运行中库存 Key 丢失会显式报错，余额需要人工对账。
- TX3 在同一个 MySQL 事务里写唯一结算账本并条件扣库存。库存完成的订单 CAS 同时登记发奖义务。普通发奖消息在本地模拟处理器完成 CAS 后确认，重复消息保持原发放时间。真实外部渠道上线需要以订单号做渠道幂等或查单。

## Outbox 变化与代价

库存正常路径删除了提交后事件和应用首发队列。库存与发奖都移除了发送回调、SENT/FAIL 状态、投递代数和发送结果 SQL。剩余补偿只扫描到期未完成义务，领取时 CAS 移动核对时间。

库存保留 10 分钟核对入口，覆盖 Half Message 超出 Broker 回查窗口或库存消费耗尽重投次数。发奖保留首发和持久化补偿，覆盖库存完成后到发奖消息发布之间的进程退出、发送失败和消费失败。库存分歧进入 BLOCKED。人工确认并修正奖池库存后，将目标订单从 4 改回 0 且设置 stock_retry_at 为当前时间，再由库存核对任务投递。

中奖请求增加同步 Broker 往返，并在持有参与锁时执行 Redis Lua。单 Broker 开启同步刷盘，供故障及容量验证。Broker 停机时命中奖品的请求进入可重试错误，保留已登记的参与和原 requestId。生产多副本和真实外部渠道尚未验证。实验分支直接使用新库，无旧测试数据迁移。

## 故障验收

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
