# 跨层字段与事实来源

## 跟踪一个字段的完整路径

用当前 requestId 的路径作参考：[DrawRequest.java](../../../lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/dto/DrawRequest.java) 的请求约束 → [LotteryController.java](../../../lottery-interfaces/src/main/java/com/anontokyo/lottery/interfaces/web/LotteryController.java) 的 DTO 转换 → [DrawProcessReq.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/req/DrawProcessReq.java) → [ActivityProcessImpl.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/process/draw/impl/ActivityProcessImpl.java) → [PartakeReq.java](../../../lottery-domain/src/main/java/com/anontokyo/lottery/domain/activity/model/req/PartakeReq.java) → [DrawParticipationRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/DrawParticipationRepository.java) → [DrawParticipation_Mapper.xml](../../../lottery-interfaces/src/main/resources/mybatis/mapper/DrawParticipation_Mapper.xml) → 实际数据库列与唯一约束。

新增字段沿这条实际链路检查传递和查询，不能只改请求对象或只加数据库列。订单消息的路径对应 DrawOrderVO、DrawOrder、Mapper、AwardFulfillmentEvent/AwardStockSettlementVO、Kafka 序列化与消费者；takeId 回放依赖订单 SELECT 与 DrawOrderVO 快照映射。

## 分清业务事实

| 标识或状态 | 作用 | 不能据此推导 |
| --- | --- | --- |
| requestId | 同一抽奖请求的 take 查询与唯一语义 | 仅有 Redis 锁就能永久幂等 |
| takeId / take state | 一次参与与是否已消费 | 未中奖也创建了订单 |
| orderId | 订单、库存账本与发奖消息关联 | Kafka send 成功即发奖成功 |
| stock_settlement_event_status | 库存消息推进状态，SENT 超时 10 分钟可恢复 | 发送成功等同结算完成 |
| fulfillment_event_status | 发奖消费状态，新 COMPLETE 要求 award_fulfillment_status=1 | 历史 fulfillment_event_status=1 必然已实际发奖 |
| award_fulfillment_status | 当前本地发奖状态 | 模拟渠道 CAS 等同真实外部恰好一次 |

依据 [OrderRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/OrderRepository.java)、[ActivityPrizeRepository.java](../../../lottery-infrastructure/src/main/java/com/anontokyo/lottery/infrastructure/repository/ActivityPrizeRepository.java)、[StockSettlementListener.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/mq/consumer/StockSettlementListener.java)、[LotteryScheduledTasks.java](../../../lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryScheduledTasks.java) 与 [DrawOrder_Mapper.xml](../../../lottery-interfaces/src/main/resources/mybatis/mapper/DrawOrder_Mapper.xml)。详细偏差见[应用规范](../backend/application-guidelines.md)。

## 确认真正的提交边界

当前在线模型使用单一 MySQL 库。个人次数扣减与参与记录创建、take 消费与订单写入、奖品扣减与唯一结算账本分别在本地事务中提交；Redis 与 MySQL、Kafka 与 MySQL 之间没有覆盖所有步骤的本地事务。改动只对已有提交边界负责，遇到 crash/replay 需求先定义实际恢复条件，不直接扩大事务范围或引入跨库事务方案。

文档与源码不一致时先读取具体实现与 Mapper，再记录证据。V2 参与表上的历史路由注释不代表当前部署仍使用分库；数据库迁移以当前单库 Schema 和按版本执行的迁移脚本为准，详见[数据库规范](../backend/database-guidelines.md)。
