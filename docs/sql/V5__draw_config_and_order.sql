-- MySQL 8.0。停应用、Kafka 消费者和补偿任务，备份三个库后，用 mysql 执行一次。
-- 前置结构见 draw-schema-refactor.md。客户端遇错必须停止，DDL 会隐式提交。
SET NAMES utf8mb4;

DELIMITER $$
CREATE PROCEDURE lottery.migrate_draw_config_and_order()
BEGIN
    DECLARE shard INT DEFAULT 0;
    DECLARE physical_table VARCHAR(96);
    DECLARE existing_index INT;
    DECLARE problem VARCHAR(128);

    -- 写入任何业务表前检查全部八张订单表。历史 UUID 可与订单号不同，原值及唯一键一并保留。
    IF (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema IN ('lottery_01','lottery_02')
          AND table_name REGEXP '^user_strategy_export_00[0-3]$'
          AND column_name IN ('take_id','stock_mq_state')) <> 16
        OR (SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema IN ('lottery_01','lottery_02') AND table_name='user_take_activity'
              AND column_name IN ('request_id','unfinished_activity_id')) <> 4
        OR NOT EXISTS (SELECT 1 FROM information_schema.tables
            WHERE table_schema='lottery' AND table_name='award_stock_settlement')
        OR EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema='lottery' AND table_name='activity'
              AND column_name IN ('stock_count','stock_surplus_count')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Missing V5 prerequisites: check Outbox, V2, V3 and V4';
    END IF;

    WHILE shard < 8 DO
        SET physical_table = CONCAT('lottery_0', 1 + shard DIV 4,
            '.user_strategy_export_', LPAD(shard MOD 4, 3, '0'));
        SET @sql = CONCAT('SELECT COUNT(*) - COUNT(DISTINCT order_id) INTO @order_conflicts FROM ', physical_table);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
        IF @order_conflicts > 0 THEN
            SET problem = CONCAT('Null or duplicate order_id in ', physical_table);
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=problem;
        END IF;
        SET shard = shard + 1;
    END WHILE;

    ALTER TABLE lottery.activity
        CHANGE COLUMN strategy_id draw_config_id BIGINT NULL COMMENT '抽奖配置ID';
    ALTER TABLE lottery.strategy
        CHANGE COLUMN strategy_id draw_config_id BIGINT NOT NULL COMMENT '抽奖配置ID',
        CHANGE COLUMN strategy_desc draw_config_desc VARCHAR(128) CHARACTER SET utf8mb4 NULL COMMENT '抽奖配置描述',
        CHANGE COLUMN grant_date scheduled_at DATETIME NULL COMMENT '计划发奖时间',
        MODIFY COLUMN grant_type TINYINT NULL COMMENT '历史发放方式配置（1即时、2定时、3人工），当前流程不调度',
        DROP COLUMN strategy_mode,
        DROP COLUMN ext_info,
        RENAME INDEX strategy_strategyId_uindex TO uk_draw_config_id,
        COMMENT '抽奖配置';
    ALTER TABLE lottery.strategy_detail
        CHANGE COLUMN strategy_id draw_config_id BIGINT NOT NULL COMMENT '抽奖配置ID',
        DROP COLUMN award_name,
        COMMENT '抽奖配置奖品及库存';
    -- 压测迁移可能已经装配同一查询索引，复用现有索引。
    IF EXISTS (SELECT 1 FROM information_schema.statistics
        WHERE table_schema='lottery' AND table_name='strategy_detail' AND index_name='idx_strategy_id_award_id') THEN
        ALTER TABLE lottery.strategy_detail RENAME INDEX idx_strategy_id_award_id TO idx_draw_config_award;
    ELSE
        ALTER TABLE lottery.strategy_detail ADD KEY idx_draw_config_award (draw_config_id,award_id);
    END IF;
    ALTER TABLE lottery.award_stock_settlement
        CHANGE COLUMN strategy_id draw_config_id BIGINT NULL COMMENT '抽奖配置ID';

    SET shard = 0;
    WHILE shard < 8 DO
        SET physical_table = CONCAT('lottery_0', 1 + shard DIV 4,
            '.user_strategy_export_', LPAD(shard MOD 4, 3, '0'));

        -- 订单号与历史 UUID 各自保留分片内唯一性，新订单仅写订单号。
        -- 计划与实际时间独立存储。后续 CAS 只写实际完成时间。
        SET @sql = CONCAT('ALTER TABLE ', physical_table,
            ' MODIFY COLUMN order_id BIGINT NOT NULL COMMENT ''抽奖订单ID'',',
            ' ADD UNIQUE KEY uk_order_id (order_id),',
            ' CHANGE COLUMN uuid legacy_uuid VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT ''历史防重标识'',',
            ' RENAME INDEX idx_uuid TO uk_legacy_uuid,',
            ' CHANGE COLUMN strategy_id draw_config_id BIGINT NULL COMMENT ''抽奖配置ID'',',
            ' DROP COLUMN strategy_mode,',
            ' CHANGE COLUMN grant_date scheduled_at DATETIME NULL COMMENT ''下单时的计划发奖时间'',',
            ' ADD COLUMN fulfilled_at DATETIME NULL COMMENT ''实际发奖完成时间'' AFTER scheduled_at,',
            ' MODIFY COLUMN grant_type TINYINT NULL COMMENT ''下单时的历史发放方式快照'',',
            ' CHANGE COLUMN grant_state award_fulfillment_status TINYINT NULL COMMENT ''实际发奖：0待发放、1已发放、2失败'',',
            ' CHANGE COLUMN mq_state fulfillment_event_status TINYINT NULL COMMENT ''发奖事件：0待投递、1消费完成、2投递失败、3已投递'',',
            ' CHANGE COLUMN stock_mq_state stock_settlement_event_status TINYINT NOT NULL DEFAULT 1 COMMENT ''库存结算事件：0待投递、1结算完成、2投递失败、3已投递、4待对账'',',
            ' RENAME INDEX idx_stock_mq_state_create_time TO idx_stock_event_create_time,',
            ' MODIFY COLUMN take_id BIGINT NULL COMMENT ''关联参与记录'',',
            ' COMMENT ''用户抽奖订单''');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;

        -- 旧消费者完成发奖时覆盖 grant_date。只把已完成行的旧时间移到实际完成列。
        SET @sql = CONCAT('UPDATE ', physical_table,
            ' SET fulfilled_at = scheduled_at, scheduled_at = NULL',
            ' WHERE award_fulfillment_status = 1 AND scheduled_at IS NOT NULL');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;

        -- 旧发奖扫描索引有两种已发布的装配版本。统一为已验证的按发奖事实过滤的索引。
        SELECT COUNT(*) INTO existing_index FROM information_schema.statistics
            WHERE table_schema=SUBSTRING_INDEX(physical_table,'.',1)
              AND table_name=SUBSTRING_INDEX(physical_table,'.',-1) AND index_name='idx_invoice_grant_state';
        IF existing_index > 0 THEN
            SET @sql = CONCAT('ALTER TABLE ', physical_table,
                ' RENAME INDEX idx_invoice_grant_state TO idx_award_fulfillment_status');
        ELSE
            SET @sql = CONCAT('ALTER TABLE ', physical_table,
                ' ADD KEY idx_award_fulfillment_status (award_fulfillment_status)');
        END IF;
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
        IF EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema=SUBSTRING_INDEX(physical_table,'.',1)
              AND table_name=SUBSTRING_INDEX(physical_table,'.',-1) AND index_name='idx_mq_state_create_time') THEN
            SET @sql = CONCAT('ALTER TABLE ', physical_table, ' DROP INDEX idx_mq_state_create_time');
            PREPARE stmt FROM @sql;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
        END IF;
        SET shard = shard + 1;
    END WHILE;

    -- 早期部署在参与表额外保存已废弃的活动库存及参与消息状态。
    -- 最新 V4 基线已无这两列，仅对确实还存在的旧列执行删除。
    SET shard = 1;
    WHILE shard <= 2 DO
        SET physical_table = CONCAT('lottery_0', shard, '.user_take_activity');
        SELECT GROUP_CONCAT(CONCAT('DROP COLUMN ', column_name)) INTO @drops
        FROM information_schema.columns
        WHERE table_schema=SUBSTRING_INDEX(physical_table,'.',1) AND table_name='user_take_activity'
          AND column_name IN ('stock_surplus_count','partake_mq_state');
        IF @drops IS NOT NULL THEN
            -- 显式删除旧消息索引，避免 MySQL 将其缩成 create_time 单列索引。
            IF EXISTS (SELECT 1 FROM information_schema.statistics
                WHERE table_schema=SUBSTRING_INDEX(physical_table,'.',1)
                  AND table_name='user_take_activity' AND index_name='idx_partake_mq_state_create_time') THEN
                SET @drops = CONCAT('DROP INDEX idx_partake_mq_state_create_time,', @drops);
            END IF;
            SET @sql = CONCAT('ALTER TABLE ', physical_table, ' ', @drops);
            PREPARE stmt FROM @sql;
            EXECUTE stmt;
            DEALLOCATE PREPARE stmt;
        END IF;
        SET shard = shard + 1;
    END WHILE;
END$$
DELIMITER ;

CALL lottery.migrate_draw_config_and_order();
DROP PROCEDURE lottery.migrate_draw_config_and_order;

-- 两库的参与表各一张。保留真实参与时间、请求归属、生成列及原唯一约束。

ALTER TABLE lottery_01.user_take_activity
    DROP COLUMN activity_name,
    CHANGE COLUMN take_date participated_at DATETIME NULL COMMENT '请求进入抽奖参与流程的时间',
    CHANGE COLUMN strategy_id draw_config_id INT NULL COMMENT '抽奖配置ID',
    MODIFY COLUMN request_id VARBINARY(256) NULL COMMENT '逻辑抽奖请求号',
    DROP INDEX idx_uId_activityId_state_id,
    ADD KEY idx_user_activity_state_id (u_id,activity_id,state,id) COMMENT '按用户活动读取未消费参与记录',
    COMMENT '抽奖参与记录';
ALTER TABLE lottery_01.user_take_activity_count
    MODIFY COLUMN total_count INT NULL COMMENT '首次参与时的次数额度快照',
    MODIFY COLUMN left_count INT NULL COMMENT '剩余参与次数',
    RENAME INDEX idx_uId_activityId TO uk_user_activity,
    COMMENT '用户活动次数额度';

ALTER TABLE lottery_02.user_take_activity
    DROP COLUMN activity_name,
    CHANGE COLUMN take_date participated_at DATETIME NULL COMMENT '请求进入抽奖参与流程的时间',
    CHANGE COLUMN strategy_id draw_config_id INT NULL COMMENT '抽奖配置ID',
    MODIFY COLUMN request_id VARBINARY(256) NULL COMMENT '逻辑抽奖请求号',
    DROP INDEX idx_uId_activityId_state_id,
    ADD KEY idx_user_activity_state_id (u_id,activity_id,state,id) COMMENT '按用户活动读取未消费参与记录',
    COMMENT '抽奖参与记录';
ALTER TABLE lottery_02.user_take_activity_count
    MODIFY COLUMN total_count INT NULL COMMENT '首次参与时的次数额度快照',
    MODIFY COLUMN left_count INT NULL COMMENT '剩余参与次数',
    RENAME INDEX idx_uId_activityId TO uk_user_activity,
    COMMENT '用户活动次数额度';

-- 一次切换全局表和两个用户库的物理表名，应用只使用新版结构。
RENAME TABLE
    lottery.strategy TO lottery.draw_config,
    lottery.strategy_detail TO lottery.draw_config_prize,
    lottery_01.user_take_activity TO lottery_01.draw_participation,
    lottery_01.user_take_activity_count TO lottery_01.user_activity_quota,
    lottery_01.user_strategy_export_000 TO lottery_01.draw_order_000,
    lottery_01.user_strategy_export_001 TO lottery_01.draw_order_001,
    lottery_01.user_strategy_export_002 TO lottery_01.draw_order_002,
    lottery_01.user_strategy_export_003 TO lottery_01.draw_order_003,
    lottery_02.user_take_activity TO lottery_02.draw_participation,
    lottery_02.user_take_activity_count TO lottery_02.user_activity_quota,
    lottery_02.user_strategy_export_000 TO lottery_02.draw_order_000,
    lottery_02.user_strategy_export_001 TO lottery_02.draw_order_001,
    lottery_02.user_strategy_export_002 TO lottery_02.draw_order_002,
    lottery_02.user_strategy_export_003 TO lottery_02.draw_order_003;

-- 规则树已退出业务链路。升级前应备份历史规则数据。
DROP TABLE lottery.rule_tree_node_line, lottery.rule_tree_node, lottery.rule_tree;
