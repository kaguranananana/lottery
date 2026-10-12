# 设计
参与记录归属与是否存在均查询 MySQL，不再用 Redis 未消费缓存决策。VO 携带 requestId；领域复用仅接受同 requestId，其他请求返回未完成状态及 pendingRequestId；无归属历史记录显式报错。HTTP 原 requestId 保持回显，新增 pendingRequestId 支持丢失原 ID 的恢复。
分片库各一张 user_take_activity 增加二进制 request_id 和 UNIQUE(u_id,activity_id,request_id)，通过生成列建立 UNIQUE(u_id,unfinished_activity_id)，保证锁过期后不同请求也不能同时创建未完成记录。次数与插入的已有事务负责回滚。迁移检查历史冲突并显式中断，不自动丢弃历史参与。
补齐订单 take 查询路由。CAS 竞争失败重读已消费记录回放，防止未中奖响应覆盖真实中奖结果。
