-- Add award stock settlement outbox state to sharded order tables.
-- Existing rows are backfilled as COMPLETE because historical versions deducted DB stock synchronously.

USE lottery_01;
ALTER TABLE user_strategy_export_000 ADD COLUMN stock_mq_state TINYINT(4) NOT NULL DEFAULT 1 COMMENT '??????MQ???0??/????1???2???' AFTER mq_state;
ALTER TABLE user_strategy_export_000 ADD INDEX idx_stock_mq_state_create_time (stock_mq_state, create_time);
ALTER TABLE user_strategy_export_001 ADD COLUMN stock_mq_state TINYINT(4) NOT NULL DEFAULT 1 COMMENT '??????MQ???0??/????1???2???' AFTER mq_state;
ALTER TABLE user_strategy_export_001 ADD INDEX idx_stock_mq_state_create_time (stock_mq_state, create_time);
ALTER TABLE user_strategy_export_002 ADD COLUMN stock_mq_state TINYINT(4) NOT NULL DEFAULT 1 COMMENT '??????MQ???0??/????1???2???' AFTER mq_state;
ALTER TABLE user_strategy_export_002 ADD INDEX idx_stock_mq_state_create_time (stock_mq_state, create_time);
ALTER TABLE user_strategy_export_003 ADD COLUMN stock_mq_state TINYINT(4) NOT NULL DEFAULT 1 COMMENT '??????MQ???0??/????1???2???' AFTER mq_state;
ALTER TABLE user_strategy_export_003 ADD INDEX idx_stock_mq_state_create_time (stock_mq_state, create_time);

USE lottery_02;
ALTER TABLE user_strategy_export_000 ADD COLUMN stock_mq_state TINYINT(4) NOT NULL DEFAULT 1 COMMENT '??????MQ???0??/????1???2???' AFTER mq_state;
ALTER TABLE user_strategy_export_000 ADD INDEX idx_stock_mq_state_create_time (stock_mq_state, create_time);
ALTER TABLE user_strategy_export_001 ADD COLUMN stock_mq_state TINYINT(4) NOT NULL DEFAULT 1 COMMENT '??????MQ???0??/????1???2???' AFTER mq_state;
ALTER TABLE user_strategy_export_001 ADD INDEX idx_stock_mq_state_create_time (stock_mq_state, create_time);
ALTER TABLE user_strategy_export_002 ADD COLUMN stock_mq_state TINYINT(4) NOT NULL DEFAULT 1 COMMENT '??????MQ???0??/????1???2???' AFTER mq_state;
ALTER TABLE user_strategy_export_002 ADD INDEX idx_stock_mq_state_create_time (stock_mq_state, create_time);
ALTER TABLE user_strategy_export_003 ADD COLUMN stock_mq_state TINYINT(4) NOT NULL DEFAULT 1 COMMENT '??????MQ???0??/????1???2???' AFTER mq_state;
ALTER TABLE user_strategy_export_003 ADD INDEX idx_stock_mq_state_create_time (stock_mq_state, create_time);
