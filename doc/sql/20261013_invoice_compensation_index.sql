-- 发奖补偿保留原谓词，先按 grant_state != 1 定位未完成发奖义务。
-- 仅新增单列非唯一索引，避免每次 stock_mq_state 推进都维护额外组合键。
-- 执行前核对目标实例与八张物理表；同名索引已存在时显式报错，不重复执行。
-- 原唯一约束、库存扫描索引和全部业务数据保持原样。

-- lottery_01 的四张订单表分别建立发奖状态访问路径。
ALTER TABLE lottery_01.user_strategy_export_000 ADD INDEX idx_invoice_grant_state (grant_state), ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE lottery_01.user_strategy_export_001 ADD INDEX idx_invoice_grant_state (grant_state), ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE lottery_01.user_strategy_export_002 ADD INDEX idx_invoice_grant_state (grant_state), ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE lottery_01.user_strategy_export_003 ADD INDEX idx_invoice_grant_state (grant_state), ALGORITHM=INPLACE, LOCK=NONE;

-- lottery_02 使用相同索引，避免某一分库继续扫描全部已完成历史。
ALTER TABLE lottery_02.user_strategy_export_000 ADD INDEX idx_invoice_grant_state (grant_state), ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE lottery_02.user_strategy_export_001 ADD INDEX idx_invoice_grant_state (grant_state), ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE lottery_02.user_strategy_export_002 ADD INDEX idx_invoice_grant_state (grant_state), ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE lottery_02.user_strategy_export_003 ADD INDEX idx_invoice_grant_state (grant_state), ALGORITHM=INPLACE, LOCK=NONE;
