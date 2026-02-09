/*
 抽奖系统测试数据补丁
 
 功能说明：
 1. 支持普通抽奖（doDraw）
 2. 支持量化人群抽奖（doQuantificationDraw）
 
 规则树决策逻辑（treeId=2110081902）：
 - 男性 + 年龄<25  → 活动ID=100001
 - 男性 + 年龄>=25 → 活动ID=100002
 - 女性 + 年龄<25  → 活动ID=100003
 - 女性 + 年龄>=25 → 活动ID=100004
 
 使用说明：
 - lottery.sql：主库（活动、策略、奖品、规则树配置）
 - lottery_01.sql / lottery_02.sql：分库（用户活动参与记录）
 - 本文件：补充测试数据，确保所有抽奖场景可用
 
 Date: 2026-02-09
 -- ============================================================
 -- 数据验证查询（可选执行）
 -- ============================================================
 /*
 -- 检查活动数据
 SELECT activity_id, activity_name, begin_date_time, end_date_time, state, strategy_id 
 FROM lottery.activity 
 WHERE activity_id IN (100001, 100002, 100003, 100004);
 
 -- 检查策略数据
 SELECT strategy_id, strategy_desc, strategy_mode 
 FROM lottery.strategy 
 WHERE strategy_id IN (10001, 10002, 10003);
 
 -- 检查规则树节点
 SELECT id, tree_id, node_type, node_value, rule_key, rule_desc 
 FROM lottery.rule_tree_node 
 WHERE tree_id = 2110081902;
 
 -- 检查规则树连线
 SELECT id, tree_id, node_id_from, node_id_to, rule_limit_type, rule_limit_value 
 FROM lottery.rule_tree_node_line 
 WHERE tree_id = 2110081902;
 
 -- 检查用户抽奖次数
 SELECT u_id, activity_id, total_count, left_count 
 FROM lottery_01.user_take_activity_count;
 */