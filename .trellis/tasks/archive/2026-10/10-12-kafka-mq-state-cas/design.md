# 设计

根因位于 UserStrategyExport_Mapper.xml 的无条件 UPDATE；预读完成状态和 Redis marker 无法约束后续数据库写入。

数据库按事件限制源状态：发送成功 0/2→3；发送失败 0→2（2 幂等不更新）；消费完成 0/2/3→1；库存消费失败用专用入口 0/3→2；所有 1→其它状态禁止。同状态不更新 update_time。发奖 COMPLETE 要求 grant_state=1，消费者重复分支也收口 mq_state。

库存 SENT 超时 10 分钟重扫；发奖保留 INIT 30 分钟、SENT 10 分钟与 FAIL 扫描，要求库存完成且 grant_state 未完成。旧补偿发送成功会写 mq_state=1，真实代码证明需要扫描这类未发奖的历史行（10 分钟），无需数据库字段迁移。发送回调不刷新 SENT，避免乱序回调无限延后补偿。

修改边界：Mapper、库存消费失败接口的 DAO/Repository/domain 透传、两个消费者、发奖补偿成功回调、测试及消息规范。异步接口保持 void，DAO 返回影响行数供测试验证。SQL 异常继续传播，零更新是合法事件被忽略，不模拟 SQL 错误为成功。

验证使用可控 Future 重排回调、Mock 验证应用入口；隔离 MySQL 执行生产 Mapper 和 Repository，验证 SQL 条件及并发。Kafka/Redis/外部渠道不宣称真实端到端覆盖。无需重构、消息版本号或外部渠道接入。
