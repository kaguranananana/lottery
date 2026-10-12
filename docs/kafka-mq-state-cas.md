# Kafka 迟到回调与消息状态修复

> 本文保留此前验证记录；当前库存业务分歧已改为 BLOCKED=4，HTTP 和预热故障验证已补齐，最新契约与结果以[最终报告](final-correctness-e2e.md)为准。

## 根因与完整调用链

中奖订单在用户分片事务内以 stock_mq_state=INIT、mq_state=INIT 创建。LotteryXxlJob 的库存 Outbox 扫描经 AwardOrderImpl → OrderRepository → IUserStrategyExportDao → UserStrategyExport_Mapper.xml 读取待投递订单，KafkaProducer 按 orderId 发消息，Future 回调异步写 SENT 或 FAIL。

LotteryStrategyAwardStockListener 查询订单，经 StrategyRepository 在全局库事务内以 UNIQUE(order_id) 账本和库存条件扣减实现幂等结算，再写用户分片库存 COMPLETE、发送发奖消息并 ack。这两个提交边界不属于同一个跨库事务。发送前预读 COMPLETE 和 Redis dispatch marker 无法限制之后的异步数据库写入。

修复前两个消息状态 UPDATE 都只以 uId/orderId 定位，无状态条件，真实 MySQL 回归复现了 `INIT → COMPLETE → SENT`（期望 1，实际 3）。发送失败回调同样可以覆盖完成状态。发奖链还有独立语义问题：普通发送成功写 SENT，XXL-Job 补偿成功却写 COMPLETE，LotteryInvoiceListener 根据 grant_state 判断完成但不收口 mq_state。

修复将保护条件放入生产 Mapper 的原子 UPDATE，保持 Repository 与现有分片调用链。无需先读再写，也没有新增表、版本字段、重试框架或 Maven 依赖。

## 状态流转规则

| 事件 | 可推进的状态 | 其它处理 |
| --- | --- | --- |
| 发送成功 | INIT(0)/FAIL(2) → SENT(3) | SENT、COMPLETE 不更新 |
| 发送失败 | INIT(0) → FAIL(2) | FAIL、SENT、COMPLETE 不更新 |
| 库存消费成功 | INIT/FAIL/SENT → COMPLETE(1) | COMPLETE 幂等不更新 |
| 发奖消费成功 | INIT/FAIL/SENT → COMPLETE(1)，要求 grant_state=1 | 已完成不更新 |
| 库存消费失败 | INIT/SENT → FAIL，使用独立 markStockSettlementFailed | FAIL、COMPLETE 不更新 |

COMPLETE 不允许回退。FAIL 可以重试成功进入 SENT，消费者不必等待发送回调，可以直接从 INIT 或 FAIL 完成。多个重试回调乱序时任意已发送成功事实优于发送失败通知；消费失败是独立业务事件，所以仍允许 SENT → FAIL。这里没有发送尝试版本号，不声称能判断每条迟到通知所属的最新尝试；数据库完成事实始终优先。

重复或被条件拒绝的事件影响行数为 0，不修改 update_time。数据库异常按原调用链向上传播；消费者收口失败不会 ack，重放时已发奖分支会重新收口 mq_state。DAO 返回 int 供影响行数验证，领域接口保留 void 更新方法。

## 补偿与历史数据

库存扫描继续包含 INIT/FAIL，并新增 update_time 超过 10 分钟的 SENT。这样发送曾成功但消费长期未完成的订单，以及超时重试后发送失败的订单都能继续被扫描；旧失败通知不会将 SENT 降为 FAIL，重复成功也不会延后恢复窗口。

发奖扫描仍要求库存 COMPLETE；FAIL 立即扫描、INIT 按 create_time 超过 30 分钟扫描、SENT 按 update_time 超过 10 分钟扫描，且 grant_state 未完成。历史补偿误写出的 `mq_state=1, grant_state=0` 也按 10 分钟超时扫描，防止修复终态保护后将这些真实未发奖订单永久排除。新发送代码均写 SENT，只有消费者确认发奖后写 COMPLETE。

库存消费分歧仍写 FAIL 并告警、ack，沿用 Outbox 后续重扫，仍需人工对账。超时 SENT 重投成功仍保持原时间，在消费未完成期间可被后续批次再次扫描；重复结算依赖数据库账本，发奖当前依赖本地 CAS。

## 修改范围

- UserStrategyExport_Mapper.xml：消息状态 CAS、消费失败条件入口、两条补偿扫描。
- IUserStrategyExportDao.java、OrderRepository.java、IOrderRepository.java、IAwardOrder.java、AwardOrderImpl.java：独立库存消费失败事件沿现有层次透传。
- LotteryStrategyAwardStockListener.java：库存失败改用独立消费事件。
- LotteryInvoiceListener.java：正常完成、重复已发奖、并发已完成分支在 ack 前收口 mq_state。
- LotteryXxlJob.java：发奖补偿成功统一写 SENT，失败日志保留 cause。
- MqCallbackStateTest.java、MqStateCasManualIT.java：应用接线与真实 MySQL SQL 回归。
- README、架构说明与 Trellis 消息规范：同步状态契约；保留任务开始前的未提交改动。

## 可重复验证

本次使用 Amazon Corretto Java 8、专用空 MySQL 9.6/InnoDB 实例（端口 13308），未使用现有业务库。ManualIT 自行创建并删除 lottery_mq_cas_it；须连接专用空实例，账号 root、空密码。没有启动真实 Kafka、Redis 或 XXL-Job 服务。

```bash
# 默认单元门禁，包含应用回调与消费接线测试。
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn clean test

# 单独运行真实 MySQL 状态验证；URL 不带库名且以 / 结尾。
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn test \
  -Dtest=MqCallbackStateTest,MqStateCasManualIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dlottery.it.mysql=jdbc:mysql://127.0.0.1:13308/

# Maven reactor 打包。
JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn package
```

修复前应用回归 5/5 失败，真实 MySQL 终态回退用例 1/1 失败。修复后定向验证 20/20 通过（7 个应用单测、13 个 MySQL 用例），默认单测 33/33 通过，Maven package 全模块成功，生成 Lottery.war。

数据库用例执行生产 Mapper、OrderRepository、实际时间函数和 InnoDB 行锁；覆盖全部四状态、消费者先完成、成功/失败回调迟到、库存与发奖多轮回调乱序、消费者重放、历史错误 COMPLETE 恢复、超时 SENT 发送失败后仍可重投。并发用例做 20 轮，每轮 12 个任务，以独立连接更新同一订单，并通过 Future.get 传播线程异常，每轮两条消息状态都停留 COMPLETE。

验证边界：MySQL 中的状态更新与扫描是真实 SQL；KafkaProducer 使用可控 Future，Redis guard 和库存业务结算使用 Mock；发奖使用当前模拟渠道处理器写真实本地 grant_state。测试不装配分片插件，所以不证明八分表路由、实际 Kafka 回调时机、真实 Redis marker 或外部发奖渠道端到端正确性。该轮为 MySQL 9.6；随后已完成 Docker MySQL 8、Redis、Kafka 的真实端到端验证，见[中间件验证记录](kafka-middleware-e2e.md)。

## 剩余风险

部署应同时更新发送者与消费者，新旧实例混跑时旧无条件 UPDATE 仍可覆盖新状态。超时恢复会产生重复消息，真实外部发奖接入仍需 orderId 幂等契约；当前本地模拟发奖不证明外部副作用恰好一次。库存分歧 FAIL 的重扫语义及共享 update_time 字段沿用现有模型，不在本次引入独立对账终态或消息时间字段。

后续端到端验证：8/8 通过，17 个订单与 17 条账本一致，两条 Kafka 消费组已提交分区 LAG 均为 0，详见[中间件验证记录](kafka-middleware-e2e.md)。
