-- 仅用于验证；先用 V7 Schema 建好专用库，再显式选择该库。
INSERT INTO award (award_id,award_type,award_name) VALUES ('fixture-prize',3,'当前目录');
INSERT INTO draw_config (draw_config_id,draw_config_desc,grant_type,scheduled_at) VALUES
(501,'旧奖池',2,'2030-01-01'),(502,'第二个奖池',3,NULL);

-- 两个旧配置号分别等于另一个活动号，检测把旧配置号误认作新活动号的问题。
INSERT INTO activity (activity_id,draw_config_id,state,begin_date_time,end_date_time,take_count) VALUES
(502,501,5,'2025-01-01','2030-01-01',3),(501,502,4,'2025-01-01','2030-01-01',3);
INSERT INTO draw_config_prize (draw_config_id,award_id,award_rate,award_count,award_surplus_count) VALUES
(501,'fixture-prize',1,5,4),(502,'fixture-prize',0,7,7);
INSERT INTO user_activity_quota (u_id,activity_id,total_count,left_count) VALUES ('historic',502,3,1);
INSERT INTO draw_participation (u_id,take_id,activity_id,draw_config_id,state,request_id) VALUES
('historic',81,502,501,1,'historic-win'),('historic',82,502,501,0,'historic-resume');
INSERT INTO draw_order (u_id,order_id,take_id,activity_id,draw_config_id,award_id,award_type,award_name,
grant_type,scheduled_at,legacy_uuid,fulfilled_at,award_fulfillment_status,fulfillment_event_status,
stock_settlement_event_status,stock_attempt,fulfillment_attempt) VALUES
('historic',91,81,502,501,'fixture-prize',3,'旧订单快照',2,'2030-01-01','historic-91','2026-10-01 01:00:00.123',1,1,1,3,4);
INSERT INTO award_stock_settlement (u_id,order_id,draw_config_id,award_id,create_time)
VALUES ('historic',91,501,'fixture-prize','2026-10-01 01:00:00');
