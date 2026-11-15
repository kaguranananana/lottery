-- 前向兼容的库存分桶迁移：只给新建活动分桶，已运行活动保持原模式。
-- 禁止在有分桶奖品或未完成消息时回滚至不支持分桶的旧版应用。
-- stock_bucket_count=0 表示原单行库存；>0 表示存储在 award_stock_bucket。
-- 必须先执行本迁移，再在新建活动时启用 lottery.stock.bucket-count。
USE lottery;
ALTER TABLE activity_prize
  ADD COLUMN stock_bucket_count SMALLINT UNSIGNED NOT NULL DEFAULT 0
  COMMENT '0=单行库存；正数=该奖品使用的桶数，创建后不可变';
CREATE TABLE award_stock_bucket (
  activity_id BIGINT NOT NULL,
  award_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  bucket_id SMALLINT UNSIGNED NOT NULL,
  initial_count INT NOT NULL,
  remaining_count INT NOT NULL,
  PRIMARY KEY (activity_id, award_id, bucket_id),
  CONSTRAINT fk_bucket_prize FOREIGN KEY (activity_id, award_id)
    REFERENCES activity_prize(activity_id, award_id),
  CONSTRAINT ck_bucket_stock CHECK (remaining_count BETWEEN 0 AND initial_count),
  CONSTRAINT ck_bucket_id CHECK (bucket_id < 64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='奖品库存分桶；结算时不再更新 activity_prize 的物理库存字段';

ALTER TABLE activity_prize
  ADD CONSTRAINT ck_activity_prize_bucket_count CHECK (stock_bucket_count BETWEEN 0 AND 64);

-- Canonical readable balance for both legacy rows and bucketed awards.
-- Do not interpret activity_prize.award_surplus_count as live stock when bucket_count > 0.
CREATE OR REPLACE VIEW v_activity_prize_stock_balance AS
SELECT p.activity_id, p.award_id, p.award_count, p.stock_bucket_count,
       CASE WHEN p.stock_bucket_count = 0 THEN p.award_surplus_count
            ELSE (SELECT SUM(b.remaining_count) FROM award_stock_bucket b
                  WHERE b.activity_id = p.activity_id AND b.award_id = p.award_id)
       END AS remaining_count
FROM activity_prize p;
