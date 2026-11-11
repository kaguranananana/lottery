-- 仅对已经合并的单库执行。USE 目标数据库后运行，执行期间停止旧应用写入。
ALTER TABLE activity
  ADD KEY idx_activity_start (state,begin_date_time,id),
  ADD KEY idx_activity_end (state,end_date_time,id);
ALTER TABLE draw_order
  ADD COLUMN stock_retry_at DATETIME(3) NULL COMMENT '库存消息下一次可投递时间' AFTER stock_settlement_event_status,
  ADD COLUMN stock_attempt INT NOT NULL DEFAULT 0 COMMENT '库存投递次数和回调代数' AFTER stock_retry_at,
  ADD COLUMN fulfillment_retry_at DATETIME(3) NULL COMMENT '发奖消息下一次可投递时间' AFTER stock_attempt,
  ADD COLUMN fulfillment_attempt INT NOT NULL DEFAULT 0 COMMENT '发奖投递次数和回调代数' AFTER fulfillment_retry_at,
  ADD COLUMN stock_settled_at DATETIME(3) NULL COMMENT '库存完成状态写入时间' AFTER fulfillment_attempt,
  MODIFY COLUMN fulfilled_at DATETIME(3) DEFAULT NULL COMMENT '实际发奖完成时间',
  MODIFY COLUMN create_time DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  DROP KEY idx_stock_event_create_time,
  ADD KEY idx_stock_retry (stock_retry_at,order_id),
  ADD KEY idx_fulfillment_retry (fulfillment_retry_at,order_id);

-- BEGIN OUTBOX BACKFILL
UPDATE draw_order
SET stock_retry_at = CASE
      WHEN stock_settlement_event_status IN (0,2) THEN NOW(3)
      WHEN stock_settlement_event_status = 3 THEN COALESCE(TIMESTAMPADD(MINUTE,10,update_time),NOW(3))
      ELSE NULL END,
    fulfillment_retry_at = CASE
      WHEN stock_settlement_event_status = 1 AND COALESCE(award_fulfillment_status,0) != 1 THEN
        CASE WHEN fulfillment_event_status IN (1,3)
             THEN COALESCE(TIMESTAMPADD(MINUTE,10,update_time),NOW(3)) ELSE NOW(3) END
      ELSE NULL END,
    fulfillment_event_status = CASE
      WHEN award_fulfillment_status = 1 THEN 1
      WHEN COALESCE(fulfillment_event_status,1) = 1 THEN 3
      ELSE fulfillment_event_status END;
