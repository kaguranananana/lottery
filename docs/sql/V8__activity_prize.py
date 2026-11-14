#!/usr/bin/env python3
"""审计并生成 V7 → V8 旁路迁移 SQL；用 mysql 批处理执行，不能带 --force。"""
import argparse
import re
from pathlib import Path

BASE = Path(__file__).resolve().parent


def identifier(name):
    if not re.fullmatch(r'[a-z][a-z0-9_]{0,63}', name):
        raise ValueError(f'无效的库名：{name}')
    return f'`{name}`'


def audit(source):
    # 每条冲突返回业务身份；共享配置必须人工裁定，绝不复制多份原库存。
    checks = {
        'shared_pool': """SELECT CONCAT_WS(':',draw_config_id,GROUP_CONCAT(activity_id ORDER BY activity_id))
            FROM activity GROUP BY draw_config_id HAVING COUNT(*) > 1""",
        'unmapped_activity': """SELECT a.activity_id FROM activity a
            LEFT JOIN draw_config c USING(draw_config_id)
            WHERE c.id IS NULL OR a.state IS NULL OR a.state NOT IN (1,4,5,7)""",
        'orphan_config': """SELECT c.draw_config_id FROM draw_config c
            LEFT JOIN activity a USING(draw_config_id) WHERE a.id IS NULL""",
        'missing_pool': """SELECT a.activity_id FROM activity a LEFT JOIN draw_config_prize p
            USING(draw_config_id) WHERE p.id IS NULL""",
        'invalid_prize': """SELECT p.id FROM draw_config_prize p LEFT JOIN activity a USING(draw_config_id)
            LEFT JOIN award w ON w.award_id=p.award_id
            WHERE a.id IS NULL OR w.id IS NULL OR BINARY p.award_id <> BINARY w.award_id OR p.award_id='' OR p.award_count IS NULL
               OR p.award_surplus_count IS NULL OR p.award_rate IS NULL
               OR p.award_rate NOT BETWEEN 0 AND 1 OR p.award_surplus_count NOT BETWEEN 0 AND p.award_count""",
        'duplicate_prize': """SELECT CONCAT_WS(':',draw_config_id,award_id)
            FROM draw_config_prize GROUP BY draw_config_id,award_id HAVING COUNT(*) > 1""",
        'excess_probability': """SELECT draw_config_id FROM draw_config_prize
            GROUP BY draw_config_id HAVING SUM(award_rate) > 1""",
        'unmapped_participation': """SELECT p.id FROM draw_participation p LEFT JOIN activity a
            ON a.activity_id=p.activity_id WHERE a.id IS NULL OR NOT (a.draw_config_id <=> p.draw_config_id)
            OR (p.state=0 AND (p.request_id IS NULL OR LENGTH(p.request_id)=0 OR p.take_id IS NULL))""",
        'unmapped_quota': """SELECT q.id FROM user_activity_quota q
            LEFT JOIN activity a USING(activity_id) WHERE a.id IS NULL""",
        'unmapped_order': """SELECT o.order_id FROM draw_order o LEFT JOIN activity a ON a.activity_id=o.activity_id
            LEFT JOIN draw_config_prize d ON d.draw_config_id=a.draw_config_id AND d.award_id=o.award_id
            LEFT JOIN draw_participation p ON p.take_id=o.take_id
            WHERE a.id IS NULL OR d.id IS NULL OR NOT (a.draw_config_id <=> o.draw_config_id)
               OR (o.take_id IS NOT NULL AND (p.id IS NULL OR NOT (BINARY p.u_id <=> BINARY o.u_id
                  AND p.activity_id <=> o.activity_id AND p.state <=> 1)))""",
        'undrained_order': """SELECT order_id FROM draw_order
            WHERE NOT (stock_settlement_event_status <=> 1 AND fulfillment_event_status <=> 1
            AND award_fulfillment_status <=> 1) OR stock_retry_at IS NOT NULL OR fulfillment_retry_at IS NOT NULL""",
        'missing_ledger': """SELECT o.order_id FROM draw_order o LEFT JOIN award_stock_settlement l
            USING(order_id) WHERE o.stock_settlement_event_status=1 AND l.id IS NULL""",
        'unmapped_ledger': """SELECT l.order_id FROM award_stock_settlement l LEFT JOIN draw_order o USING(order_id)
            WHERE o.id IS NULL OR NOT (BINARY o.u_id <=> BINARY l.u_id AND o.draw_config_id <=> l.draw_config_id
                AND BINARY o.award_id <=> BINARY l.award_id)""",
        'unbalanced_stock': """SELECT p.id FROM draw_config_prize p LEFT JOIN
            (SELECT draw_config_id,award_id,COUNT(*) AS settled FROM award_stock_settlement GROUP BY 1,2) l
            ON l.draw_config_id=p.draw_config_id AND l.award_id=p.award_id
            WHERE NOT (p.award_count-p.award_surplus_count <=> COALESCE(l.settled,0))""",
    }
    return f"""USE {identifier(source)};
-- 仅会话临时表，不改源库业务表。
CREATE TEMPORARY TABLE v8_conflict (issue varchar(32), identity text);
""" + '\n'.join(f"INSERT INTO v8_conflict SELECT '{issue}', bad.* FROM ({query}) bad;"
                   for issue, query in checks.items()) + """
SELECT issue, COUNT(*) AS conflicts FROM v8_conflict GROUP BY issue ORDER BY issue;
SELECT issue, identity FROM v8_conflict ORDER BY issue, identity LIMIT 100;
SELECT 'unfinished_requests' AS fact,COUNT(*) AS rows_found FROM draw_participation WHERE state=0
UNION ALL SELECT 'orders_without_take_id',COUNT(*) FROM draw_order WHERE take_id IS NULL;
CREATE TEMPORARY TABLE v8_gate (conflicts BIGINT, CONSTRAINT v8_conflicts_require_resolution CHECK (conflicts=0));
INSERT INTO v8_gate SELECT COUNT(*) FROM v8_conflict;
"""


def migration(source, target):
    src, dest = identifier(source), identifier(target)
    if source == target or not target.startswith('lottery_v8'):
        raise ValueError('目标必须是新的 lottery_v8* 库，且与源库不同')
    ddl = (BASE / 'lottery-v8-schema.sql').read_text()
    ddl = ddl.replace('CREATE DATABASE lottery ', f'CREATE DATABASE {dest} ').replace('USE lottery;', f'USE {dest};')
    copy, verify = [], []
    for table, definition in re.findall(r'CREATE TABLE `(\w+)` \(\n(.*?)\n\) ENGINE=', ddl, re.S):
        columns = [f'`{name}`' for name, spec in re.findall(r'^  `(\w+)` (.*)', definition, re.M)
                   if 'GENERATED ALWAYS' not in spec]
        from_clause = f'{src}.`{table}` s'
        expressions = {c: f's.{c}' for c in columns}
        if table == 'activity_prize':
            from_clause = f'{src}.draw_config_prize s JOIN {src}.activity a USING(draw_config_id)'
            expressions['`activity_id`'] = 'a.activity_id'
        if table == 'award_stock_settlement':
            from_clause += f' JOIN {src}.draw_order o USING(order_id)'
            expressions['`activity_id`'] = 'o.activity_id'
        # 显式复制全部保留字段；历史配置及发奖计划可通过只读源库按业务 ID 核对。
        copy.append(f'INSERT INTO `{table}` ({", ".join(columns)}) '
                    f'SELECT {", ".join(expressions.values())} FROM {from_clause} ORDER BY s.id;')
        # 中奖概率扩大了小数位数，比较数值；标识、快照等原值逐字节核对。
        equal = ' AND '.join(f'(t.{c} <=> {expressions[c]})' if c == '`award_rate`'
                             else f'(BINARY t.{c} <=> BINARY {expressions[c]})' for c in columns)
        verify.append(f"""SELECT '{table}' AS copied_table, COUNT(*) AS rows_copied FROM `{table}`;
SELECT '{table}' AS checked_table, COUNT(*) AS mismatched_rows FROM {from_clause}
LEFT JOIN `{table}` t ON t.id=s.id WHERE t.id IS NULL OR NOT ({equal});
INSERT INTO {src}.v8_gate SELECT COUNT(*) FROM {from_clause}
LEFT JOIN `{table}` t ON t.id=s.id WHERE t.id IS NULL OR NOT ({equal});""")
    return ("""SET SESSION sql_mode = 'STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION';
""" + audit(source) + ddl + """
-- DDL 已在复制前提交；后续只有目标 DML。遇错 mysql 退出并回滚数据事务，保留目标结构供检查。
START TRANSACTION;
""" + '\n'.join(copy + verify) + '\nCOMMIT;\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--audit-only', action='store_true')
    group.add_argument('--target')
    args = parser.parse_args()
    print(audit(args.source) if args.audit_only else migration(args.source, args.target))
