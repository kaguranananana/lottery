SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- Dedicated seed for JMeter load testing.
-- This script prepares:
-- 1) one dedicated high-load activity (activity_id = 109001)
-- 2) one dedicated strategy with large prize stock (strategy_id = 10901)
-- 3) pre-created user_take_activity_count rows for jmeter_u_000001 ~ jmeter_u_020000
-- 4) cleanup for old test orders of the same user prefix

SET @load_activity_id = 109001;
SET @load_strategy_id = 10901;
SET @user_prefix = 'jmeter_u_';
SET @user_like = 'jmeter_u_%';
SET @user_count = 20000;
SET @user_take_count = 100;
SET @activity_stock = 200000;

START TRANSACTION;

INSERT INTO lottery.strategy
    (strategy_id, strategy_desc, strategy_mode, grant_type, grant_date, ext_info, create_time, update_time)
VALUES
    (@load_strategy_id, 'JMeter high load strategy', 2, 1, NULL, 'seed:20260314_jmeter_pressure', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    strategy_desc = VALUES(strategy_desc),
    strategy_mode = VALUES(strategy_mode),
    grant_type = VALUES(grant_type),
    grant_date = VALUES(grant_date),
    ext_info = VALUES(ext_info),
    update_time = NOW();

DELETE FROM lottery.strategy_detail
WHERE strategy_id = @load_strategy_id;

INSERT INTO lottery.strategy_detail
    (strategy_id, award_id, award_name, award_count, award_surplus_count, award_rate, create_time, update_time)
VALUES
    (@load_strategy_id, '1', 'IMac',    4000,   4000,   0.02, NOW(), NOW()),
    (@load_strategy_id, '2', 'iphone',  16000,  16000,  0.08, NOW(), NOW()),
    (@load_strategy_id, '3', 'ipad',    40000,  40000,  0.20, NOW(), NOW()),
    (@load_strategy_id, '4', 'AirPods', 60000,  60000,  0.30, NOW(), NOW()),
    (@load_strategy_id, '5', 'Book',    80000,  80000,  0.40, NOW(), NOW());

INSERT INTO lottery.activity
    (activity_id, activity_name, activity_desc, begin_date_time, end_date_time, stock_count,
     stock_surplus_count, take_count, strategy_id, state, creator, create_time, update_time)
VALUES
    (@load_activity_id, 'JMeter Load Activity', 'Dedicated activity for HTTP gateway load testing',
     '2025-01-01 00:00:00', '2035-12-31 23:59:59', @activity_stock, @activity_stock,
     @user_take_count, @load_strategy_id, 5, 'codex', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    activity_name = VALUES(activity_name),
    activity_desc = VALUES(activity_desc),
    begin_date_time = VALUES(begin_date_time),
    end_date_time = VALUES(end_date_time),
    stock_count = VALUES(stock_count),
    stock_surplus_count = VALUES(stock_surplus_count),
    take_count = VALUES(take_count),
    strategy_id = VALUES(strategy_id),
    state = VALUES(state),
    creator = VALUES(creator),
    update_time = NOW();

COMMIT;

START TRANSACTION;

DELETE FROM lottery_01.user_take_activity
WHERE activity_id = @load_activity_id
  AND u_id LIKE @user_like;

DELETE FROM lottery_02.user_take_activity
WHERE activity_id = @load_activity_id
  AND u_id LIKE @user_like;

DELETE FROM lottery_01.user_take_activity_count
WHERE activity_id = @load_activity_id
  AND u_id LIKE @user_like;

DELETE FROM lottery_02.user_take_activity_count
WHERE activity_id = @load_activity_id
  AND u_id LIKE @user_like;

DELETE FROM lottery_01.user_strategy_export_000
WHERE activity_id = @load_activity_id
  AND u_id LIKE @user_like;

DELETE FROM lottery_01.user_strategy_export_001
WHERE activity_id = @load_activity_id
  AND u_id LIKE @user_like;

DELETE FROM lottery_01.user_strategy_export_002
WHERE activity_id = @load_activity_id
  AND u_id LIKE @user_like;

DELETE FROM lottery_01.user_strategy_export_003
WHERE activity_id = @load_activity_id
  AND u_id LIKE @user_like;

DELETE FROM lottery_02.user_strategy_export_000
WHERE activity_id = @load_activity_id
  AND u_id LIKE @user_like;

DELETE FROM lottery_02.user_strategy_export_001
WHERE activity_id = @load_activity_id
  AND u_id LIKE @user_like;

DELETE FROM lottery_02.user_strategy_export_002
WHERE activity_id = @load_activity_id
  AND u_id LIKE @user_like;

DELETE FROM lottery_02.user_strategy_export_003
WHERE activity_id = @load_activity_id
  AND u_id LIKE @user_like;

COMMIT;

DROP PROCEDURE IF EXISTS lottery.seed_jmeter_users;
DELIMITER $$
CREATE PROCEDURE lottery.seed_jmeter_users(
    IN p_activity_id BIGINT,
    IN p_user_count INT,
    IN p_take_count INT
)
BEGIN
    DECLARE v_idx INT DEFAULT 1;
    DECLARE v_u_id VARCHAR(32);

    WHILE v_idx <= p_user_count DO
        SET v_u_id = CONCAT(@user_prefix, LPAD(v_idx, 6, '0'));

        INSERT INTO lottery_01.user_take_activity_count
            (u_id, activity_id, total_count, left_count, create_time, update_time)
        VALUES
            (v_u_id, p_activity_id, p_take_count, p_take_count, NOW(), NOW())
        ON DUPLICATE KEY UPDATE
            total_count = VALUES(total_count),
            left_count = VALUES(left_count),
            update_time = NOW();

        INSERT INTO lottery_02.user_take_activity_count
            (u_id, activity_id, total_count, left_count, create_time, update_time)
        VALUES
            (v_u_id, p_activity_id, p_take_count, p_take_count, NOW(), NOW())
        ON DUPLICATE KEY UPDATE
            total_count = VALUES(total_count),
            left_count = VALUES(left_count),
            update_time = NOW();

        SET v_idx = v_idx + 1;
    END WHILE;
END$$
DELIMITER ;

CALL lottery.seed_jmeter_users(@load_activity_id, @user_count, @user_take_count);
DROP PROCEDURE IF EXISTS lottery.seed_jmeter_users;

SELECT
    activity_id,
    strategy_id,
    stock_count,
    stock_surplus_count,
    take_count,
    state
FROM lottery.activity
WHERE activity_id = @load_activity_id;

SELECT
    strategy_id,
    SUM(award_count) AS total_award_stock,
    SUM(award_surplus_count) AS total_award_surplus,
    COUNT(*) AS award_type_count
FROM lottery.strategy_detail
WHERE strategy_id = @load_strategy_id
GROUP BY strategy_id;

SELECT COUNT(*) AS db01_seeded_users
FROM lottery_01.user_take_activity_count
WHERE activity_id = @load_activity_id
  AND u_id LIKE @user_like;

SELECT COUNT(*) AS db02_seeded_users
FROM lottery_02.user_take_activity_count
WHERE activity_id = @load_activity_id
  AND u_id LIKE @user_like;

SET FOREIGN_KEY_CHECKS = 1;
