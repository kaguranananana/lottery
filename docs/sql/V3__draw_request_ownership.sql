-- 发布前停止抽奖写入并备份；mysql 客户端直接执行本文件（不要使用 --force）。
-- 当前参与记录仅分库：lottery_01/02.user_take_activity，并非八张参与分表。
-- 已有 V2 request_id 列/uk_request_id 的环境也可执行；历史未知归属不自动补造。
DELIMITER $$
DROP PROCEDURE IF EXISTS lottery_01.migrate_draw_request_ownership$$
CREATE PROCEDURE lottery_01.migrate_draw_request_ownership(IN target_schema VARCHAR(64))
BEGIN
    DECLARE conflict_count INT DEFAULT 0;
    -- 未完成重复记录必须人工核查，迁移不得选择一条并丢弃其他参与机会。
    SET @sql = CONCAT('SELECT COUNT(*) INTO @conflicts FROM (SELECT u_id, activity_id FROM `',
        target_schema, '`.user_take_activity WHERE state=0 GROUP BY u_id,activity_id HAVING COUNT(*)>1) c');
    PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    IF @conflicts > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Multiple unfinished takes: reconcile before migration';
    END IF;
    -- 未知状态与空用户/活动无法建立有效归属，先核查，禁止自动解释为已完成。
    SET @sql = CONCAT('SELECT COUNT(*) INTO @conflicts FROM `', target_schema,
        '`.user_take_activity WHERE u_id IS NULL OR activity_id IS NULL OR state IS NULL OR state NOT IN (0,1)');
    PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    IF @conflicts > 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unknown take identity/state: reconcile before migration';
    END IF;
    SELECT COUNT(*) INTO conflict_count FROM information_schema.columns
        WHERE table_schema=target_schema AND table_name='user_take_activity' AND column_name='request_id';
    IF conflict_count=0 THEN
        SET @sql=CONCAT('ALTER TABLE `',target_schema,
            '`.user_take_activity ADD COLUMN request_id VARBINARY(256) NULL COMMENT ''逻辑抽奖请求号'' AFTER state');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    ELSE
        -- 64 个字符按 UTF-8 最多占 256 字节，不缩短现有 HTTP 字符长度契约。
        -- 请求号按字节比较，保证大小写和尾部空格与 Java equals 一致。
        SET @sql=CONCAT('ALTER TABLE `',target_schema,'`.user_take_activity MODIFY request_id VARBINARY(256) NULL');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
    -- 同一用户活动内的请求号必须唯一；有历史冲突时 ALTER 显式失败。
    SELECT COUNT(*) INTO conflict_count FROM information_schema.statistics
        WHERE table_schema=target_schema AND table_name='user_take_activity' AND index_name='uk_request_id';
    IF conflict_count>0 THEN
        SET @sql=CONCAT('ALTER TABLE `',target_schema,'`.user_take_activity DROP INDEX uk_request_id');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
    SELECT COUNT(*) INTO conflict_count FROM information_schema.statistics
        WHERE table_schema=target_schema AND table_name='user_take_activity' AND index_name='uk_user_activity_request';
    IF conflict_count=0 THEN
        SET @sql=CONCAT('ALTER TABLE `',target_schema,
            '`.user_take_activity ADD UNIQUE KEY uk_user_activity_request(u_id,activity_id,request_id)');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
    SELECT COUNT(*) INTO conflict_count FROM information_schema.columns
        WHERE table_schema=target_schema AND table_name='user_take_activity' AND column_name='unfinished_activity_id';
    IF conflict_count=0 THEN
        -- 已消费行生成 NULL，允许历史多轮；未消费行生成活动号，限制每用户活动仅一条。
        SET @sql=CONCAT('ALTER TABLE `',target_schema,
            '`.user_take_activity ADD COLUMN unfinished_activity_id BIGINT GENERATED ALWAYS AS (CASE WHEN state=0 THEN activity_id ELSE NULL END) STORED');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
    SELECT COUNT(*) INTO conflict_count FROM information_schema.statistics
        WHERE table_schema=target_schema AND table_name='user_take_activity' AND index_name='uk_user_unfinished_activity';
    IF conflict_count=0 THEN
        SET @sql=CONCAT('ALTER TABLE `',target_schema,
            '`.user_take_activity ADD UNIQUE KEY uk_user_unfinished_activity(u_id,unfinished_activity_id)');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;
CALL lottery_01.migrate_draw_request_ownership('lottery_01');
CALL lottery_01.migrate_draw_request_ownership('lottery_02');
DROP PROCEDURE lottery_01.migrate_draw_request_ownership;
-- 订单仍按用户分表；只补齐同步结果回放必需的关联列，不改变异步结算字段。
DELIMITER $$
DROP PROCEDURE IF EXISTS lottery_01.migrate_draw_order_take$$
CREATE PROCEDURE lottery_01.migrate_draw_order_take(IN target_schema VARCHAR(64))
BEGIN
    DECLARE table_index INT DEFAULT 0;
    DECLARE column_count INT;
    WHILE table_index < 4 DO
        SET @table_name=CONCAT('user_strategy_export_',LPAD(table_index,3,'0'));
        SELECT COUNT(*) INTO column_count FROM information_schema.columns
            WHERE table_schema=target_schema AND table_name=@table_name AND column_name='take_id';
        IF column_count=0 THEN
            SET @sql=CONCAT('ALTER TABLE `',target_schema,'`.`',@table_name,
                '` ADD COLUMN take_id BIGINT NULL COMMENT ''关联参与记录'' AFTER order_id');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        END IF;
        SELECT COUNT(*) INTO column_count FROM information_schema.statistics
            WHERE table_schema=target_schema AND table_name=@table_name AND index_name='idx_u_id_take_id';
        IF column_count=0 THEN
            SET @sql=CONCAT('ALTER TABLE `',target_schema,'`.`',@table_name,
                '` ADD KEY idx_u_id_take_id(u_id,take_id)');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        END IF;
        SET table_index=table_index+1;
    END WHILE;
END$$
DELIMITER ;
CALL lottery_01.migrate_draw_order_take('lottery_01');
CALL lottery_01.migrate_draw_order_take('lottery_02');
DROP PROCEDURE lottery_01.migrate_draw_order_take;

-- 检查两库 SHOW CREATE TABLE user_take_activity 的两个唯一键与生成列后再发布应用。
-- 订单 stock_mq_state 等仍按既有异步结算迁移部署，本次不改变其语义。
