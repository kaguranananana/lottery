# 抽奖配置、订单与数据库升级

## 命名和数据审计

| 原名称 | 当前名称 | 业务用途 |
| --- | --- | --- |
| `strategy`、`strategy_detail`、`strategy_id` | `draw_config`、`draw_config_prize`、`draw_config_id` | 活动引用的抽奖配置、奖品概率和库存。Java 对应 `DrawConfigRepository` 和 `DrawConfigSnapshot`。 |
| `user_strategy_export_000…003` | `draw_order_000…003` | 两个用户库各四张订单表，仍按 `uId` 路由。 |
| `user_take_activity`、`user_take_activity_count` | `draw_participation`、`user_activity_quota` | 每个用户库各一张。参与行保存请求归属，额度行负责条件扣减。 |
| `grant_state`、`mq_state`、`stock_mq_state` | `award_fulfillment_status`、`fulfillment_event_status`、`stock_settlement_event_status` | 发奖事实、发奖事件与库存结算事件各有枚举。 |
| `InvoiceVO` | `AwardFulfillmentEvent` | 发奖消息载荷。HTTP 回放从 `DrawOrderVO` 读取历史快照。 |

| 清理对象 | 代码证据和影响 |
| --- | --- |
| 配置及订单的 `strategy_mode` | `WeightedRandomSelector` 只执行加权随机，旧模式只经快照、抽奖结果和订单透传。删除该列、Java 属性及枚举。 |
| 配置 `ext_info` | Mapper 和仓储原先只映射预留值，抽奖快照和算法无消费者。清理 PO、VO 和 Mapper。 |
| 配置奖品 `award_name` | 旧 Mapper 只写。抽奖名称从 `award` 读取，订单继续持久化下单时的名称和内容。 |
| 参与 `activity_name` | 旧 Mapper 只写，归属查询和参与 CAS 均使用 ID。清理参与 PO 与 `ActivityBillVO` 的冗余透传。 |
| 旧参与表 `stock_surplus_count`、`partake_mq_state` | 一套早期部署仍有这两列及旧参与消息索引。参与仓储只处理个人次数与请求归属，库存义务由订单 Outbox 持有。升级时移除实际存在的旧列和索引。 |
| `Award.awardCount` 及奖品摘要属性 | `Award_Mapper.xml` 无对应列。初始库存和余额均保留在配置奖品中。 |
| 三张 `rule_tree*` 表 | 当前 Java、DAO、Mapper、运行脚本没有调用。只读核查的两个 MySQL 实例在三个业务库中也没有视图、触发器或存储过程依赖。新库省略三表，V5 在完成改名后删除，旧 SQL 保留。 |

- 订单 `legacy_uuid` 和原唯一约束保留供历史核对。教程种子中存在与 `order_id` 不同的旧 UUID。只读核查的两个实例共 4 笔现存订单，均为当前格式，8 张分表分别检查无空订单号、无重复。V5 先检查全部分表，才新增分片内 `UNIQUE(order_id)`。应用按 `uId + order_id` 查询，新订单无历史 UUID。全局账本的订单唯一键继续保护结算。
- `take_date` 改为 `participated_at`。它来自参与请求时间，实库确有记录晚一秒入库。保留 `create_time`、`request_id`、`take_id`、未完成生成列及其唯一键。
- `grant_type` 和 `scheduled_at` 保留计划配置及下单快照。现有流程按奖品类型选择模拟处理器，未使用发放方式或时间调度。已完成旧订单的 `grant_date` 转入 `fulfilled_at`，其原计划时间已被旧代码覆盖，保持 NULL；未完成订单的日期保留在 `scheduled_at`。新发奖 CAS 只写 `fulfilled_at`。
- `total_count` 记录首次参与时的额度，压测业务审计用它与 `left_count` 核对消耗。奖品配置初始库存与剩余库存、订单奖品快照及全局结算账本继续各自保存。现有数据模型保留 ID 关联；三库结构没有物理外键。参与表配置 ID 仍是 INT，额度表用户 ID 仍是 VARCHAR(20)，此次沿用历史存储宽度。
- 补偿索引保留库存状态及创建时间、实际发奖状态、用户与参与 ID。旧发奖消息状态索引由实际发奖状态索引接替。全局配置奖品索引沿用已验证的 `(draw_config_id, award_id)` 访问路径。

## 状态与兼容契约

发奖事件和库存结算事件都使用 `0 INIT` 待投递、`2 FAIL` 投递失败待重试、`3 SENT` 已投递等待消费、`1 COMPLETE` 业务事实已落库。库存另有 `4 BLOCKED` 待人工对账。实际发奖使用 `0 INIT`、`1 COMPLETE`、`2 FAIL`。历史上发奖发送成功曾写成 `1`，保留未发奖订单按超时补偿的既有路径。

发奖流程只在库存结算完成后推进。库存完成后，迟到库存回调的 CAS 不刷新 `update_time`。实际发奖完成后，补偿查询排除该单，即使事件尚未标记完成。共用时间列在这两个有效扫描阶段互不干扰，沿用原来的 30 分钟 INIT 和 10 分钟 SENT 时限、CAS 和账本幂等。

HTTP 请求和响应字段、Kafka topic/group/key 与 JSON 字段 `strategyId`、`grantDate`、XXL-Job handler、Redis 实时库存与处理标记 Key 均沿用已有契约。HTTP 回放的 `grantDate` 优先展示已保存的实际完成时间。只切换会从 MySQL 重建的活动缓存和抽奖快照 Key，避免用新版 Java 读取旧 JSON。旧缓存按原 TTL 到期。

## 执行顺序

1. **新库**执行 [lottery-schema.sql](sql/lottery-schema.sql)。脚本创建三个库、17 张表，无历史迁移依赖。可选执行 [lottery-demo-seed.sql](sql/lottery-demo-seed.sql)，再通过活动生命周期任务预热奖池并开启活动。
2. **已有库**停旧版 HTTP 写入、消费者和 XXL-Job 任务。备份三个库并验证可恢复性，核对 SQL 脚本、视图、触发器及下游查询的引用。升级要求八张订单表已具备库存 Outbox 和 `take_id`，账本已建，参与表具备 V3 请求约束，活动表已移除 V4 的两列。只补尚未执行的历史迁移。运行库中一套已符合前置结构，另一套还需补 V2、V3、V4，库存 Outbox 已存在。
3. 在备份副本演练 [V5__draw_config_and_order.sql](sql/V5__draw_config_and_order.sql)，再通过 MySQL 客户端执行一次。遇到订单号为空或分片内重复时，先按报出的物理表核对历史数据。MySQL DDL 会分步提交，执行错误须停发版、依据备份和已完成步骤恢复。仅前置或订单号检查失败，且确认没有业务表变更时，清理 `lottery.migrate_draw_config_and_order` 临时过程再执行。已部分执行的库先按备份恢复。
4. 用新版应用检查 17 张表、八分片回放、积压补偿与库存余额，再恢复流量。回退应用时须同时恢复旧结构和数据。真实库存 Key 与 Kafka 积压应随原有部署流程维护。

旧版 `doc/sql/` 基线、历史种子和 V2–V4 保留为升级参考。可运行 `python3 docs/sql/verify_draw_schema.py` 在一次性 MySQL 容器里比较新建与旧库升级结果。旧图解和压测报告记录当时的代码与表名。

## 验证结果

- Java 8 `mvn clean package` 通过 49 项默认测试，包含旧 Kafka 字段往返序列化。
- 独立 MySQL、Redis、Kafka 上通过 70 项集成测试。覆盖 HTTP 八分表请求回放、真实 Kafka 重复及乱序、Broker 重启补偿、Redis 预扣回补、库存 BLOCKED、发奖状态与消息状态独立、计划和实际发奖时间、奖品目录变更后的快照回放，以及旧 V4 迁移。
- 独立 MySQL 8.0.46 验证新建与升级后 17 张表定义一致，八表拒绝重复订单号，历史 UUID、两类发奖时间、参与时点、订单奖品快照和账本行保留。末张分表出现订单号冲突时，V5 在修改首张业务表前中止。另在一次性实例还原两套现有库的只读导出并分别升级成功。
- 本机 `13306` 和 `13329` 已在备份还原演练后完成 V5。演示服务通过 HTTP、Redis、Kafka 和 MySQL 完成实际抽奖、回放、结算与模拟发奖，两个消费组无积压。其他实例升级仍需停写、备份及演练。外部发奖渠道、外部 XXL-Job Admin 和容量未在本轮验证。
