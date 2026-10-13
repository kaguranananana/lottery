# 技术设计

创建活动：在配置聚合内绑定策略号，Repository 初始化剩余库存，Mapper 显式插入两个字段。策略明细新增初始库存与所属策略由新建配置确定。

启动：Repository 使用现有 TransactionTemplate，在全局库 SELECT FOR UPDATE 读取活动；只在 PASS 且时间已到、尚未结束时 SETNX 完整库存，再 CAS DOING。失败抛出并回滚状态；部分 Redis 成功保留，下轮 PASS 可补齐。运行中不允许自动回填缺失 key，重复启动只返回 false。直接预热入口也使用相同行锁与 PASS 限制。

结算：stock_mq_state 新增 BLOCKED=4，消费条件扣减为零且账本事务回滚时通过独立事件 CAS 写 BLOCKED，写入失败不得 ACK。INIT/FAIL/SENT 仍补偿；BLOCKED 不受发送回调影响。真实账本成功允许推进 COMPLETE（含并发成功覆盖 BLOCKED）。对账修复数据后，人工在正确分片按订单 CAS 4→0，原 Outbox 再投递；不新增自动修复或泛化运维接口。

测试：复用专用测试 compose（13309/16379/19092），核实 Docker 标签、无业务挂载；旧 MQ E2E 保留。新增 HTTP 完整链路、创建事务回滚、分页、启动/预热、库存分歧持久化/释放与异常 ACK 回归。真实 broker 停机/恢复由已有 E2E 验证。外部发奖为已有模拟渠道，Handler 直接调用。
