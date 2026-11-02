-- V4：取消整场活动的总参与名额，只移除全局库 activity 的两个库存列。
-- 旧库升级：停止旧版应用/任务后，在已具备 Outbox、V2、V3 的库上执行一次，随后发布新版。
-- 新库初始化：doc/sql/lottery.sql 已不包含这两列，不再执行本迁移。
-- 本次 DDL 不可通过事务回滚；保留活动行、用户次数/参与、订单及库存结算账本。
USE `lottery`;

ALTER TABLE `activity`
    DROP COLUMN `stock_count`,
    DROP COLUMN `stock_surplus_count`;
