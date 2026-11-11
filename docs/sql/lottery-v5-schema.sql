-- MySQL 8.0 全新初始化。三个数据库须尚未创建。历史库使用 V5 升级流程。
SET NAMES utf8mb4;
CREATE DATABASE lottery CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE lottery_01 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE lottery_02 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 全局库含活动、奖品配置和库存结算账本。
USE lottery;
CREATE TABLE `activity` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `activity_id` bigint NOT NULL COMMENT '活动ID',
  `activity_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '活动名称',
  `activity_desc` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '活动描述',
  `begin_date_time` datetime DEFAULT NULL COMMENT '开始时间',
  `end_date_time` datetime DEFAULT NULL COMMENT '结束时间',
  `take_count` int DEFAULT NULL COMMENT '每人可参与次数',
  `draw_config_id` bigint DEFAULT NULL COMMENT '抽奖配置ID',
  `state` tinyint DEFAULT NULL COMMENT '活动状态：1编辑、2提审、3撤审、4通过、5运行(审核通过后worker扫描状态)、6拒绝、7关闭、8开启',
  `creator` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `unique_activity_id` (`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='活动配置';

CREATE TABLE `award` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `award_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '奖品ID',
  `award_type` tinyint DEFAULT NULL COMMENT '奖品类型（1:文字描述、2:兑换码、3:优惠券、4:实物奖品）',
  `award_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '奖品名称',
  `award_content` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '奖品内容「文字描述、Key、码」',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_award_id` (`award_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='奖品配置';

CREATE TABLE `draw_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `draw_config_id` bigint NOT NULL COMMENT '抽奖配置ID',
  `draw_config_desc` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '抽奖配置描述',
  `grant_type` tinyint DEFAULT NULL COMMENT '历史发放方式配置（1即时、2定时、3人工），当前流程不调度',
  `scheduled_at` datetime DEFAULT NULL COMMENT '计划发奖时间',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_draw_config_id` (`draw_config_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='抽奖配置';

CREATE TABLE `draw_config_prize` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `draw_config_id` bigint NOT NULL COMMENT '抽奖配置ID',
  `award_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '奖品ID',
  `award_count` int DEFAULT NULL COMMENT '奖品库存',
  `award_surplus_count` int DEFAULT '0' COMMENT '奖品剩余库存',
  `award_rate` decimal(5,2) DEFAULT NULL COMMENT '中奖概率',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  KEY `idx_draw_config_award` (`draw_config_id`,`award_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='抽奖配置奖品及库存';

CREATE TABLE `award_stock_settlement` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `order_id` bigint NOT NULL COMMENT '抽奖订单ID',
  `u_id` varchar(32) DEFAULT NULL COMMENT '用户ID',
  `draw_config_id` bigint DEFAULT NULL COMMENT '抽奖配置ID',
  `award_id` varchar(64) DEFAULT NULL COMMENT '奖品ID',
  `create_time` datetime NOT NULL COMMENT '结算时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='奖品库存结算幂等账本';

-- 用户参与及次数只分库；订单按用户分库后再分四张物理表。
USE lottery_01;
CREATE TABLE `draw_participation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `u_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '用户ID',
  `take_id` bigint DEFAULT NULL COMMENT '活动领取ID',
  `activity_id` bigint DEFAULT NULL COMMENT '活动ID',
  `participated_at` datetime DEFAULT NULL COMMENT '请求进入抽奖参与流程的时间',
  `draw_config_id` int DEFAULT NULL COMMENT '抽奖配置ID',
  `state` tinyint DEFAULT NULL COMMENT '活动单使用状态 0未使用、1已使用',
  `request_id` varbinary(256) DEFAULT NULL COMMENT '逻辑抽奖请求号',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  `unfinished_activity_id` bigint GENERATED ALWAYS AS ((case when (`state` = 0) then `activity_id` else NULL end)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_take_id` (`take_id`) USING BTREE COMMENT '活动领取ID',
  UNIQUE KEY `uk_user_activity_request` (`u_id`,`activity_id`,`request_id`),
  UNIQUE KEY `uk_user_unfinished_activity` (`u_id`,`unfinished_activity_id`),
  KEY `idx_user_activity_state_id` (`u_id`,`activity_id`,`state`,`id`) COMMENT '按用户活动读取未消费参与记录'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='抽奖参与记录';

CREATE TABLE `user_activity_quota` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `u_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '用户ID',
  `activity_id` bigint DEFAULT NULL COMMENT '活动ID',
  `total_count` int DEFAULT NULL COMMENT '首次参与时的次数额度快照',
  `left_count` int DEFAULT NULL COMMENT '剩余参与次数',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_activity` (`u_id`,`activity_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户活动次数额度';

CREATE TABLE `draw_order_000` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  `u_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '用户ID',
  `activity_id` bigint DEFAULT NULL COMMENT '活动ID',
  `order_id` bigint NOT NULL COMMENT '抽奖订单ID',
  `take_id` bigint DEFAULT NULL COMMENT '关联参与记录',
  `draw_config_id` bigint DEFAULT NULL COMMENT '抽奖配置ID',
  `grant_type` tinyint DEFAULT NULL COMMENT '下单时的历史发放方式快照',
  `scheduled_at` datetime DEFAULT NULL COMMENT '下单时的计划发奖时间',
  `fulfilled_at` datetime DEFAULT NULL COMMENT '实际发奖完成时间',
  `award_fulfillment_status` tinyint DEFAULT NULL COMMENT '实际发奖：0待发放、1已发放、2失败',
  `award_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '发奖ID',
  `award_type` tinyint DEFAULT NULL COMMENT '奖品类型（1:文字描述、2:兑换码、3:优惠券、4:实物奖品）',
  `award_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '奖品名称',
  `award_content` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '奖品内容「文字描述、Key、码」',
  `legacy_uuid` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '历史防重标识',
  `fulfillment_event_status` tinyint DEFAULT NULL COMMENT '发奖事件：0待投递、1消费完成、2投递失败、3已投递',
  `stock_settlement_event_status` tinyint NOT NULL DEFAULT '1' COMMENT '库存结算事件：0待投递、1结算完成、2投递失败、3已投递、4待对账',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_id` (`order_id`),
  UNIQUE KEY `uk_legacy_uuid` (`legacy_uuid`) USING BTREE,
  KEY `idx_stock_event_create_time` (`stock_settlement_event_status`,`create_time`),
  KEY `idx_u_id_take_id` (`u_id`,`take_id`),
  KEY `idx_award_fulfillment_status` (`award_fulfillment_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户抽奖订单';
CREATE TABLE draw_order_001 LIKE draw_order_000;
CREATE TABLE draw_order_002 LIKE draw_order_000;
CREATE TABLE draw_order_003 LIKE draw_order_000;

USE lottery_02;
CREATE TABLE draw_participation LIKE lottery_01.draw_participation;
CREATE TABLE user_activity_quota LIKE lottery_01.user_activity_quota;
CREATE TABLE draw_order_000 LIKE lottery_01.draw_order_000;
CREATE TABLE draw_order_001 LIKE lottery_01.draw_order_000;
CREATE TABLE draw_order_002 LIKE lottery_01.draw_order_000;
CREATE TABLE draw_order_003 LIKE lottery_01.draw_order_000;
