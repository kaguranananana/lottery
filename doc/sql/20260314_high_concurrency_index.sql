-- High-concurrency index supplement script
-- Execute each section in the corresponding database once.

-- ------------------------------------------------------------
-- lottery
-- ------------------------------------------------------------
USE `lottery`;

ALTER TABLE `strategy_detail`
    ADD KEY `idx_strategy_id_award_id` (`strategy_id`, `award_id`) USING BTREE COMMENT 'strategy award stock update';

-- ------------------------------------------------------------
-- lottery_01
-- ------------------------------------------------------------
USE `lottery_01`;

ALTER TABLE `user_strategy_export_000`
    ADD KEY `idx_mq_state_create_time` (`mq_state`, `create_time`) USING BTREE COMMENT 'invoice mq retry scan';

ALTER TABLE `user_strategy_export_001`
    ADD KEY `idx_mq_state_create_time` (`mq_state`, `create_time`) USING BTREE COMMENT 'invoice mq retry scan';

ALTER TABLE `user_strategy_export_002`
    ADD KEY `idx_mq_state_create_time` (`mq_state`, `create_time`) USING BTREE COMMENT 'invoice mq retry scan';

ALTER TABLE `user_strategy_export_003`
    ADD KEY `idx_mq_state_create_time` (`mq_state`, `create_time`) USING BTREE COMMENT 'invoice mq retry scan';

-- ------------------------------------------------------------
-- lottery_02
-- ------------------------------------------------------------
USE `lottery_02`;

ALTER TABLE `user_strategy_export_000`
    ADD KEY `idx_mq_state_create_time` (`mq_state`, `create_time`) USING BTREE COMMENT 'invoice mq retry scan';

ALTER TABLE `user_strategy_export_001`
    ADD KEY `idx_mq_state_create_time` (`mq_state`, `create_time`) USING BTREE COMMENT 'invoice mq retry scan';

ALTER TABLE `user_strategy_export_002`
    ADD KEY `idx_mq_state_create_time` (`mq_state`, `create_time`) USING BTREE COMMENT 'invoice mq retry scan';

ALTER TABLE `user_strategy_export_003`
    ADD KEY `idx_mq_state_create_time` (`mq_state`, `create_time`) USING BTREE COMMENT 'invoice mq retry scan';
