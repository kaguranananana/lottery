# Lottery 单库部署与 V6 迁移

> 本文记录 V6/V7 八表旁路合库。当前七表结构与部署见 [V8 活动独立奖池](activity-prize-pool.md)。

复现 V7 八表环境使用 `lottery-v7-schema.sql`。当前 `lottery-schema.sql` 与演示种子使用 V8 七表，不能用于 V7 应用。

| 表 | 身份与约束 |
| --- | --- |
| activity | 活动号唯一 |
| award | 奖品号唯一 |
| draw_config | 抽奖配置号唯一 |
| draw_config_prize | 按配置和奖品定位库存 |
| user_activity_quota | 用户与活动组合唯一，用户列与 HTTP 32 字符契约一致 |
| draw_participation | 参与号、用户活动请求号、用户活动未消费记录分别唯一 |
| draw_order | 订单号、非空参与号、非空历史 UUID 分别唯一 |
| award_stock_settlement | 订单号唯一，作为库存结算幂等账本 |

单个 Hikari 池供 MyBatis 和 Spring 事务管理使用。TX1 原子扣次数并插入参与，TX2 CAS 消费参与并写入带 Outbox 状态的订单，TX3 原子写入账本并扣减 MySQL 奖品库存。Redis Lua、Kafka 事件、消费者组配置及订单状态协议沿用原实现。活动和消息补偿由 Spring 调度。V6 旁路合并生成包含 V7 字段的最新结构。 [订单 Outbox](outbox-delivery.md)

## 旁路迁移

1. 确认源为 V5，备份 `lottery`、`lottery_01`、`lottery_02` 和 Redis 实时库存，先在隔离实例还原并演练。旧库低于 V5 时依照 [历史升级步骤](draw-schema-refactor.md) 处理。
2. 停止接收新抽奖，让旧版 Outbox 和消费者处理存量后暂停旧应用及其调度。记录在途及待对账订单、Kafka 消费位点和 Redis 实时库存。复制期间源库必须停止写入。
3. 在同一 MySQL 实例创建新的目标库。使用遇错停止的 mysql 批处理模式，禁用 `--force`。下面命令在仓库根目录运行。

   ```sh
   python3 docs/sql/V6__merge_lottery.py --target lottery_single > /tmp/lottery-v6.sql
   mysql --host=127.0.0.1 --port=13306 --user=YOUR_USER --password --batch < /tmp/lottery-v6.sql
   ```

4. 对照旧库联合计数、额度、请求、订单号、参与号、账本、Outbox 状态及两份实时库存。迁移报告的历史孤立订单或账本需逐项确认是否为原有数据。Redis 库存及 Kafka 位点按切换前记录续用，避免重复预热或重置消费者位点。
5. 设置 `LOTTERY_DB_URL` 指向 `lottery_single`，按需设置 `LOTTERY_DB_USER`、`LOTTERY_DB_PASSWORD`。启动新版后在恢复抽奖入口之前核对数据库、库存、Outbox 和消费进度。切换后已产生新业务写入时，回切需要先核对并迁回新增数据。

迁移仅向全新目标库写入。用户业务表的自增主键重编号，外部协议引用的参与号、订单号、请求号和库存账本保留。目标的唯一索引拦截跨库冲突，订单与已有参与、账本与已有订单的归属也必须一致。冲突会回滚所有数据写入并保留报错，目标空表由 DDL 创建。源库应维持停写，核查冲突后可用另一个 `lottery_single_*` 目标重新演练。历史缺失关联的记录按原值保留，避免误删历史事实。

`python3 docs/sql/verify_draw_schema.py` 在一次性 MySQL 容器验证 V5 升级、V6 跨库冲突回滚、自增主键重编号、业务关联与原状态保留。
