#!/usr/bin/env python3
"""专用测试库验证 V9 是既有单行库存的无损前向升级，并检查统一余额视图。"""
from pathlib import Path
from environment import sql, verify

SCHEMA = "lottery_stock_bucket_migration_verify"
ROOT = Path(__file__).resolve().parents[2]

def main():
    verify()
    assert sql(f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{SCHEMA}';").strip() == "0", (
        "isolated migration probe database already exists; refuse to overwrite")
    sql(f"CREATE DATABASE {SCHEMA} DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
    try:
        sql(f"""CREATE TABLE {SCHEMA}.activity_prize (
            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            activity_id BIGINT NOT NULL,
            award_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
            award_count INT NOT NULL,
            award_surplus_count INT NOT NULL,
            award_rate DECIMAL(7,6) NOT NULL,
            UNIQUE KEY uk_activity_award(activity_id,award_id),
            CONSTRAINT ck_stock CHECK (award_surplus_count BETWEEN 0 AND award_count)
        ) ENGINE=InnoDB;""")
        sql(f"INSERT INTO {SCHEMA}.activity_prize(activity_id,award_id,award_count,award_surplus_count,award_rate) VALUES (12,'old',20,17,0.5);")
        mig = (ROOT / "docs/sql/V9__stock_buckets.sql").read_text()
        assert mig.count("USE lottery;") == 1
        mig = mig.replace("USE lottery;", f"USE {SCHEMA};")
        sql(mig)
        old = sql(f"SELECT stock_bucket_count,award_count,remaining_count FROM {SCHEMA}.v_activity_prize_stock_balance WHERE activity_id=12 AND award_id='old';").strip()
        assert old == "0\t20\t17", ("legacy stock changed unexpectedly", old)
        sql(f"INSERT INTO {SCHEMA}.activity_prize(activity_id,award_id,award_count,award_surplus_count,award_rate,stock_bucket_count) VALUES (12,'bucketed',16,16,0.5,8);")
        values = ",".join(f"(12,'bucketed',{i},2,2)" for i in range(8))
        sql(f"INSERT INTO {SCHEMA}.award_stock_bucket(activity_id,award_id,bucket_id,initial_count,remaining_count) VALUES {values};")
        sql(f"UPDATE {SCHEMA}.award_stock_bucket SET remaining_count=remaining_count-1 WHERE activity_id=12 AND award_id='bucketed' AND bucket_id=3 AND remaining_count>0;")
        val = sql(f"SELECT stock_bucket_count,award_count,remaining_count FROM {SCHEMA}.v_activity_prize_stock_balance WHERE activity_id=12 AND award_id='bucketed';").strip()
        assert val == "8\t16\t15", ("bucket view balance incorrect", val)
        parent = sql(f"SELECT award_surplus_count FROM {SCHEMA}.activity_prize WHERE activity_id=12 AND award_id='bucketed';").strip()
        assert parent == "16", ("bucket mode accidentally wrote parent hot row", parent)
        print("V9 MIGRATION PASS legacy old=17/20, bucketed=15/16, parent stayed 16; all data isolated")
    finally:
        # 只删除本工具创建的专用验证库，绝不清理测试业务数据库。
        sql(f"DROP DATABASE {SCHEMA};")

if __name__ == "__main__":
    main()
