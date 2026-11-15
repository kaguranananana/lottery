# 2026-11-15 · 库存一致性与默认八桶验证

基线为 `main@6fa287d`。本轮未修改生产 Schema、历史迁移、抽奖概率或开发库数据。

| 风险 | 修复前证据 | 回归断言 |
| --- | --- | --- |
| 默认八桶未进入日常故障回归 | 旧 `mq-e2e` profile 固定单行；BLOCKED 在 HTTP 返回后才制造库存分歧 | 用默认八桶，先停消费者再注入库存分歧。真实 Broker 重投、原请求回放后，三次额度只用一次，只有一条参与、一张订单、一条账本；Redis/MySQL 均从 10 到 9，本地模拟发奖完成 |
| 失败预扣占满到期扫描窗口 | 独立 MySQL/Redis 实测：最早两条持续失败，连续扫描之后正常库存仍为 0（期望 1） | 失败项仍保留在待对账索引，延期后可重查；后续正常项恢复至 1，再扫仍为 1；已清理的索引不被过期扫描结果重建 |
| 无账本也能推进库存完成 | 真实旧 Mapper 对无账本订单更新成功 | 无账本或奖品不匹配时抛错、不登记发奖；并发 CAS 只有一个调用成功。TX3 已提交而订单 CAS 失败时，经真实 Broker 重放完成原单，账本和扣减各一次 |
| 未知活动变成系统异常 | 真实 HTTP 返回 `0001`，服务端日志出现空指针 | HTTP 仍为 200，业务码 `D010`，回显请求号，未创建额度、参与或订单 |
| 首轮整套测试受 Broker 故障用例污染 | Broker 重启期间发起的长轮询在下一例仍等待，订单库存终态耗时 30.321 秒，超过原 30 秒断言；数据库及 Broker 核对表明订单最终完成 | 停机用例先用原消费者验证实际重连，结束后关闭故障期间的客户端，让后续用例重建连接；保持原业务断言和超时 |

仍保留 V9 默认八桶与持久化单行模式、RocketMQ 事务消息、TX1/TX2/TX3 本地边界、唯一账本、原请求回放，以及库存分歧后的 BLOCKED 人工核对。测试只连接专用 Compose 和独立 SQL 实例；真实外部渠道、生产 Broker 高可用、多实例、应用 SIGKILL 和吞吐未在本轮验证。

从仓库根使用 Java 8：

```bash
mvn clean package
bash lottery-interfaces/src/test/resources/mq-e2e/verify.sh
mvn -pl lottery-interfaces -am \
  -Dtest=FinalBusinessE2eManualIT,DemoAccountsManualIT,DrawAdmissionRedisManualIT,RedisStockFailureManualIT \
  -Dlottery.it.redis.port=16379 -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl lottery-interfaces -am \
  -Dtest=FulfillmentEventStatusCasManualIT,DrawRequestOwnershipManualIT,PerformanceSqlManualIT,InvoiceCompensationIndexManualIT,JdbcLocalSessionStateManualIT \
  -Dlottery.it.mysql=jdbc:mysql://127.0.0.1:13319/ -Dlottery.perf.mysql=jdbc:mysql://127.0.0.1:13339/ \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

SQL 专项使用专用空 MySQL 13319 和已加载当前 Schema 的专用 MySQL 13339。全链路端口和用例选择见[质量规范](../../.trellis/spec/backend/quality-guidelines.md)。默认构建同时检查 Web 依赖和现行规范的本地链接。

| 检查 | 通过 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| Java 8 打包及默认测试 | 55 | 0 / 0 / 0 |
| 八桶快速入口（属于下列套件的子集） | 3 | 0 / 0 / 0 |
| HTTP / MySQL / Redis / RocketMQ 与准入回归 | 44 | 0 / 0 / 0 |
| MySQL SQL 专项 | 31 | 0 / 0 / 0 |

首轮完整中间件套件为 43 通过、1 失败，原因及修复见上表；停机与多用户用例的定向复跑 2 项通过后，再执行完整 44 项。三次修复前的定向失败分别是预扣饥饿、无账本 CAS 和未知活动空指针，未计入修复后的通过数。
