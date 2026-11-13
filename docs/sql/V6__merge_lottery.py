#!/usr/bin/env python3
"""生成旁路合库 SQL。源为已经升级到 V5 的三个只读业务库。"""
import argparse
import re
from pathlib import Path

BASE = Path(__file__).resolve().parent
SHARDS = ('lottery_01', 'lottery_02')
OUTBOX_COLUMNS = {'stock_retry_at', 'stock_attempt', 'fulfillment_retry_at', 'fulfillment_attempt', 'stock_settled_at'}
GLOBAL = ('activity', 'award', 'draw_config', 'draw_config_prize', 'award_stock_settlement')


def migration(target):
    if not re.fullmatch(r'lottery_single(?:_[a-z0-9_]{1,40})?', target):
        raise ValueError('目标库必须使用新的 lottery_single 或 lottery_single_* 名称')
    ddl = (BASE / 'lottery-v7-schema.sql').read_text()
    ddl = ddl.replace('CREATE DATABASE lottery ', f'CREATE DATABASE {target} ').replace('USE lottery;', f'USE {target};')
    # 按目标表的显式列名复制。分片自增 ID 会重编号，生成列由目标重新计算。
    statements = []
    for table, definition in re.findall(r'CREATE TABLE `(\w+)` \(\n(.*?)\n\) ENGINE=', ddl, re.S):
        columns = ', '.join(f'`{name}`' for name, spec in re.findall(r'^  `(\w+)` (.*)', definition, re.M)
                            if 'GENERATED ALWAYS' not in spec and name not in OUTBOX_COLUMNS
                            and (name != 'id' or table in GLOBAL))
        if table in GLOBAL:
            sources = [f'lottery.{table}']
        elif table == 'draw_order':
            sources = [f'{schema}.{table}_{index:03d}' for schema in SHARDS for index in range(4)]
        else:
            sources = [f'{schema}.{table}' for schema in SHARDS]
        for source in sources:
            statements.append(f'  INSERT INTO `{table}` ({columns}) SELECT {columns} FROM {source} ORDER BY id;')
    backfill = (BASE / 'V7__outbox_retry.sql').read_text().split('-- BEGIN OUTBOX BACKFILL\n')[1]
    return ddl + '''
-- 源库必须已停写。业务 ID、时间、Outbox 状态和账本原样复制。
SET SESSION sql_mode = 'STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION';
DELIMITER //
CREATE PROCEDURE merge_v5()
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;
  START TRANSACTION;
  -- 请求归属、待消费归属、订单号、历史 UUID 及 take 唯一约束跨分片生效。
''' + '\n'.join(statements) + '''
  -- 对已有参与关系核对用户、活动和配置；旧记录缺关联时仍保留并单独报告。
  IF EXISTS (SELECT 1 FROM draw_order o JOIN draw_participation p ON p.take_id = o.take_id
      WHERE NOT (BINARY o.u_id <=> BINARY p.u_id)
         OR NOT (o.activity_id <=> p.activity_id)
         OR NOT (o.draw_config_id <=> p.draw_config_id)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V6 order/participation ownership conflict';
  END IF;
  IF EXISTS (SELECT 1 FROM award_stock_settlement s JOIN draw_order o ON o.order_id = s.order_id
      WHERE NOT (BINARY o.u_id <=> BINARY s.u_id)
         OR NOT (o.draw_config_id <=> s.draw_config_id)
         OR NOT (BINARY o.award_id <=> BINARY s.award_id)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V6 settlement/order ownership conflict';
  END IF;
''' + backfill + '''  COMMIT;
END//
DELIMITER ;
CALL merge_v5();
DROP PROCEDURE merge_v5;
SELECT COUNT(*) AS historical_orders_without_participation
  FROM draw_order o LEFT JOIN draw_participation p ON o.take_id = p.take_id WHERE p.take_id IS NULL;
SELECT COUNT(*) AS historical_settlements_without_order
  FROM award_stock_settlement s LEFT JOIN draw_order o ON o.order_id = s.order_id WHERE o.order_id IS NULL;
'''


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', default='lottery_single')
    print(migration(parser.parse_args().target))
