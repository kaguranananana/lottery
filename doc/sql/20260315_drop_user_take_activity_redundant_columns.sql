/*
  Drop redundant columns from user_take_activity and switch uniqueness to take_id.

  Execute once after the application code has been updated.
  Target databases:
  - lottery_01
  - lottery_02
*/

ALTER TABLE `lottery_01`.`user_take_activity`
    DROP INDEX `idx_uuid`,
    ADD UNIQUE KEY `idx_take_id` (`take_id`) USING BTREE COMMENT '活动领取ID',
    DROP COLUMN `take_count`,
    DROP COLUMN `uuid`;

ALTER TABLE `lottery_02`.`user_take_activity`
    DROP INDEX `idx_uuid`,
    ADD UNIQUE KEY `idx_take_id` (`take_id`) USING BTREE COMMENT '活动领取ID',
    DROP COLUMN `take_count`,
    DROP COLUMN `uuid`;
