# 单实例调度与订单 Outbox

本文记录 Kafka 版本的投递协议。当前实验分支见 [RocketMQ 事务消息](rocketmq-transactional-report.md)。


Spring 默认用三个调度线程，每个任务采用独立的 10 秒固定延迟。活动扫描查询到期的 READY 和 RUNNING 行，生命周期服务在数据库行锁内复核状态并预热 Redis。单条活动异常记录活动号后继续处理同一页。扫描任务异常由 Spring 记录，下一轮继续调度。配置项见 `application.yml` 的 `spring.task.scheduling` 和 `lottery.scheduling`。

## 投递与重试

- TX2 同时提交订单、库存投递义务及首发代数。事务事件监听器只在实际提交后把任务交给共享有界工作池。HTTP 不等待 Kafka 确认。库存消费者结算后用一条 CAS 写入库存完成、发奖义务及发奖首发代数，只有该更新成功的消费者安排实时发奖。
- 两种首发义务写入时均保留两秒后的补偿时间。实时投递直接使用已持久化的代数。代数从登记首发资格开始计数，可能在进程退出或队列拒绝时先于实际发送。满载、进程退出或回调落库失败时，到期义务由定时任务领取。补偿 CAS 递增代数并设置 45 秒租期。若首发在到期后仍在途，补偿和首发可能都到达 Broker，消费者依靠原有幂等约束收敛，旧代数的回调不能推进状态。Broker 发送失败按代数从 5 秒起指数退避，最多 300 秒。发送成功后 600 秒仍未消费的订单可重发。
- 默认 6 个工作线程，128 个总在途许可涵盖排队、等待 Broker 和结果落库。Kafka 发送线程只交接回调，状态更新由工作池执行。补偿最多占用四分之一在途许可，每轮每种事件最多 3 批，每批取可用额度与 100 的较小值，等待一批最多 2 秒才决定继续。生产者单次调用等待元数据或缓冲空间至多 1 秒，交付超时 30 秒。
- 发送回调按代数和非终态 CAS 更新，库存完成、发奖完成和 BLOCKED 不接受迟到回调。库存账本按订单号唯一，重复消息不会重复扣库存。发奖处理器目前只做本地模拟，发奖事实和消息完成写在同一条 SQL。topic 和消息键保持原值。新库存消息携带活动号；新旧消息都用用户和订单号重读订单，旧 `strategyId` 不用于定位库存。在线订单只保留实际发奖时间和状态。
- 异步链路移除了固定值 Redis 标记。Kafka 同一订单按同一键分区，重复处理最终由数据库账本和 CAS 约束。HTTP 的短防重入锁仍用所有者令牌和 Lua 条件释放。

## 历史 V7 切换

已有 V6 单库先停止旧应用和调度，再备份并在隔离副本演练 [V7 SQL](sql/V7__outbox_retry.sql)。DDL 先添加两条索引和独立重试字段，回填保留现有业务状态与时间。历史误写发奖事件 COMPLETE、实际未发奖的行恢复为 SENT 并按旧超时重投。已完成历史订单缺少准确库存结算时点，因此 `stock_settled_at` 留空。全新环境执行 [完整 Schema](sql/lottery-schema.sql)。仍处于 V5 的环境使用 [V6 旁路合并](single-database.md)，目标直接生成 V7 结构并执行同一回填。当前部署见 [V8 切换](activity-prize-pool.md)。切换时保留 Kafka 位点、Redis 实时库存及待对账记录。

## 库存对账

以下查询可核对 BLOCKED 数量与订单事实。检查 Redis 该配置奖品的实时余额、MySQL 剩余量、账本及在途订单。确认预扣归属并修复差额后，记录操作人与订单号，再对指定订单释放。

```sql
SELECT COUNT(*) FROM draw_order WHERE stock_settlement_event_status = 4;
SELECT o.order_id, o.u_id, o.activity_id, o.award_id, o.stock_attempt, o.update_time,
       p.award_surplus_count, s.create_time AS ledger_time
FROM draw_order o
LEFT JOIN activity_prize p ON p.activity_id=o.activity_id AND p.award_id=o.award_id
LEFT JOIN award_stock_settlement s ON s.order_id=o.order_id
WHERE o.stock_settlement_event_status=4
ORDER BY o.order_id;

UPDATE draw_order
SET stock_settlement_event_status=0, stock_retry_at=NOW(3), stock_attempt=stock_attempt+1, update_time=NOW()
WHERE order_id=@confirmed_order_id AND stock_settlement_event_status=4;
```

释放时先递增投递代数，旧回调在下一次领取之前也无法推进状态。若账本已存在，重放只恢复订单终态与发奖义务。对账时不能直接按滞后的数据库余额重建运行期 Redis 库存。Redis 预扣后、订单提交前进程退出的恢复仍需核对。
