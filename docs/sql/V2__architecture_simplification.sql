-- =====================================================================
-- V2: architecture simplification migration
-- Date: 2026-08-27
--
-- 执行顺序：
--   1. 在全局库 lottery（db00）执行第一部分（settlement 账本）
--   2. 在两个分片库 lottery_01 / lottery_02 的 8 张分表上分别执行
--      第二部分与第三部分的 ALTER（每张物理表各执行一次）
--   3. 全部在应用发布前完成（新代码依赖这些列/表）
-- 本脚本不包含 DROP：历史数据保守处理，废弃字段仅注释供人工归并。
-- =====================================================================

-- ---------------------------------------------------------------------
-- Part 1. award_stock_settlement 结算账本（只在全局库 lottery 执行一次）
-- 与 strategy_detail 同库，保证「INSERT 账本 + 条件扣减库存」是一个本地事务；
-- UNIQUE(order_id) 是 Kafka 重复消费 / 消费者 crash 重放时防止重复扣库存的最终兜底。
-- ---------------------------------------------------------------------
USE `lottery`;

CREATE TABLE IF NOT EXISTS `award_stock_settlement` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `order_id` bigint(20) NOT NULL COMMENT '抽奖订单ID',
    `u_id` varchar(32) DEFAULT NULL COMMENT '用户ID',
    `strategy_id` bigint(20) DEFAULT NULL COMMENT '策略ID',
    `award_id` varchar(64) DEFAULT NULL COMMENT '奖品ID',
    `create_time` datetime NOT NULL COMMENT '结算时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_id` (`order_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '奖品库存结算幂等账本';

-- ---------------------------------------------------------------------
-- Part 2. 抽奖请求幂等字段（对每个分片库的每张 user_take_activity 物理表执行）
--   lottery_01.user_take_activity_000 ~ _003
--   lottery_02.user_take_activity_000 ~ _003
-- request_id 允许为 NULL：历史数据无需回填；NULL 不参与唯一约束冲突。
-- ---------------------------------------------------------------------
-- USE `lottery_01`;
-- ALTER TABLE `user_take_activity_000`
--     ADD COLUMN `request_id` varchar(64) DEFAULT NULL COMMENT '请求幂等号' AFTER `state`,
--     ADD UNIQUE KEY `uk_request_id` (`request_id`);
-- （其余 7 张分表同理）

-- ---------------------------------------------------------------------
-- Part 3. 订单表补 take 关联列（对每张 user_strategy_export 物理表执行）
--   lottery_01.user_strategy_export_000 ~ _003
--   lottery_02.user_strategy_export_000 ~ _003
-- 用于同 requestId 重试时通过 take 回放订单结果；历史行为 NULL 不影响。
-- 注：idx_stock_mq_state_create_time 已由 20260315_async_award_stock_outbox.sql 建立。
-- ---------------------------------------------------------------------
-- USE `lottery_01`;
-- ALTER TABLE `user_strategy_export_000`
--     ADD COLUMN `take_id` bigint(20) DEFAULT NULL COMMENT '关联用户参与记录ID' AFTER `order_id`,
--     ADD KEY `idx_u_id_take_id` (`u_id`, `take_id`);
-- （其余 7 张分表同理）

-- ---------------------------------------------------------------------
-- Deprecated data（不做自动迁移，保留注释说明语义变化）
--   activity.state 中历史值 2提审/3撤审/6拒绝/8开启 已不再具备生产语义，
--   当前状态机为 EDIT(1)->PASS(4)->DOING(5)->CLOSE(7)。
--   如存在历史行，人工按业务含义归并：
--     UPDATE activity SET state = 1 WHERE state IN (2, 3, 8);
--     UPDATE activity SET state = 1 WHERE state = 6;   -- 或按需要改 7 关闭
--   strategy.strategy_mode 列继续存在但不再影响算法选择（单一加权随机算法）。
--   库存恢复说明：活动 RUNNING 期间 Redis 库存 key 丢失必须走预热/人工对账，
--   不允许用滞后的 MySQL 值回填（见 docs/refactor-audit.md 第 4 节）。
-- ---------------------------------------------------------------------
