#!/usr/bin/env python3
"""在独立 MySQL 容器验证新建、旧库升级和冲突阻断。"""
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ('lottery', 'lottery_01', 'lottery_02')


def source(path):
    return (ROOT / path).read_text()


def verify(container):
    client = ['docker', 'exec', '-i', container, 'mysql', '-uroot',
              '--default-character-set=utf8mb4', '--batch', '--skip-column-names']

    def sql(script, expected_error=None):
        result = subprocess.run(client, input=script, text=True, capture_output=True, timeout=60)
        if expected_error:
            assert result.returncode and expected_error in result.stderr, result.stderr
        elif result.returncode:
            raise RuntimeError(result.stderr)
        return result.stdout.strip()

    def definitions():
        dump = subprocess.check_output(
            ['docker', 'exec', container, 'mysqldump', '-uroot', '--default-character-set=utf8mb4',
             '--no-data', '--skip-comments', '--skip-add-drop-table', '--skip-set-charset',
             '--no-tablespaces', '--databases', *SCHEMAS], text=True, timeout=30)
        # 历史数据的自增位置依旧保留，只比较结构定义。
        return re.sub(r' AUTO_INCREMENT=\d+', '', dump)

    for _ in range(30):
        ping = subprocess.run(client, input='SELECT 1;', text=True, capture_output=True, timeout=5)
        if ping.returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError(ping.stderr)

    sql(source('docs/sql/lottery-schema.sql'))
    fresh = definitions()
    assert fresh.count('CREATE TABLE `') == 17
    sql(source('docs/sql/lottery-demo-seed.sql'))
    assert sql('SELECT COUNT(*) FROM lottery.activity WHERE state=4;') == '4'

    # 只重建本脚本创建的容器。旧教程样例曾有无效参与状态，使用真实 DDL 和显式历史行。
    for schema in SCHEMAS:
        legacy = source(f'doc/sql/{schema}.sql')
        tables = re.findall(r'CREATE TABLE `[^\n]+?\n.*?\n\).*?;', legacy, re.S)
        sql(f'DROP DATABASE {schema}; CREATE DATABASE {schema}; USE {schema};\n' + '\n'.join(tables))

    # 模拟部署中实见的两种索引装配及较老参与表额外字段。
    sql("""
        ALTER TABLE lottery.activity ADD stock_count INT, ADD stock_surplus_count INT;
        ALTER TABLE lottery.strategy_detail ADD KEY idx_strategy_id_award_id (strategy_id,award_id);
        ALTER TABLE lottery_02.user_take_activity ADD stock_surplus_count INT, ADD partake_mq_state TINYINT,
            ADD KEY idx_partake_mq_state_create_time (partake_mq_state,create_time);
        ALTER TABLE lottery_02.user_strategy_export_003 ADD KEY idx_mq_state_create_time (mq_state,create_time);
    """)
    for path in ('doc/sql/20260315_async_award_stock_outbox.sql', 'docs/sql/V2__architecture_simplification.sql',
                 'docs/sql/V3__draw_request_ownership.sql', 'docs/sql/V4__remove_activity_stock.sql'):
        sql(source(path))
    sql('ALTER TABLE lottery_01.user_strategy_export_001 ADD KEY idx_invoice_grant_state (grant_state);')
    sql("""
        INSERT INTO lottery.strategy(strategy_id,strategy_mode,grant_type,grant_date)
            VALUES (7,2,2,'2026-12-01 00:00:00');
        INSERT INTO lottery_01.user_strategy_export_001
            (u_id,order_id,uuid,take_id,strategy_id,award_name,award_content,grant_state,grant_date,mq_state)
            VALUES ('u',77,'earlier-id',11,7,'下单奖品','下单内容',1,'2026-11-01 00:00:00',1),
                   ('u',78,'78',12,7,'计划发放奖品','内容',0,'2026-12-01 00:00:00',3);
        INSERT INTO lottery_01.user_take_activity
            (u_id,take_id,activity_id,state,strategy_id,request_id,take_date,create_time)
            VALUES ('u',11,7,1,7,'A','2026-11-01 00:00:00','2026-11-01 00:00:01');
        INSERT INTO lottery.award_stock_settlement(order_id,u_id,strategy_id,award_id,create_time)
            VALUES (77,'u',7,'award',NOW());
        INSERT INTO lottery_02.user_strategy_export_003(order_id,uuid) VALUES (99,'99'),(99,'other-id');
    """)
    migration = source('docs/sql/V5__draw_config_and_order.sql')
    sql(migration, 'Null or duplicate order_id in lottery_02.user_strategy_export_003')
    assert sql("SHOW COLUMNS FROM lottery.activity LIKE 'draw_config_id';") == ''
    assert sql('SELECT COUNT(*) FROM lottery_02.user_strategy_export_003;') == '2'

    # 在测试中移除刻意注入的冲突行，再从原结构重新执行迁移。
    sql("DROP PROCEDURE lottery.migrate_draw_config_and_order;"
        "DELETE FROM lottery_02.user_strategy_export_003 WHERE uuid='other-id';")
    sql(migration)
    upgraded = definitions()
    if fresh != upgraded:
        import difflib
        raise AssertionError('\n'.join(difflib.unified_diff(fresh.splitlines(), upgraded.splitlines())))
    assert sql('SELECT legacy_uuid, take_id, award_name, award_content, scheduled_at, fulfilled_at,'
               ' fulfillment_event_status FROM lottery_01.draw_order_001 ORDER BY order_id;') == (
        'earlier-id\t11\t下单奖品\t下单内容\tNULL\t2026-11-01 00:00:00\t1\n'
        '78\t12\t计划发放奖品\t内容\t2026-12-01 00:00:00\tNULL\t3')
    assert sql('SELECT grant_type, scheduled_at FROM lottery.draw_config WHERE draw_config_id=7;') == (
        '2\t2026-12-01 00:00:00')
    assert sql('SELECT request_id, participated_at, create_time FROM lottery_01.draw_participation;') == (
        'A\t2026-11-01 00:00:00\t2026-11-01 00:00:01')
    assert sql('SELECT draw_config_id FROM lottery.award_stock_settlement WHERE order_id=77;') == '7'
    for schema in SCHEMAS[1:]:
        for shard in range(4):
            table = f'{schema}.draw_order_{shard:03d}'
            sql(f"INSERT INTO {table}(u_id,order_id) VALUES ('new', 200);")
            sql(f"INSERT INTO {table}(u_id,order_id) VALUES ('different', 200);", 'uk_order_id')
    print('新建与升级后 17 张表定义一致，历史数据、冲突阻断及八分表订单唯一键通过。')


if __name__ == '__main__':
    container = subprocess.check_output(
        ['docker', 'run', '--rm', '-d', '-e', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', 'mysql:8.0',
         '--character-set-server=utf8mb4', '--collation-server=utf8mb4_0900_ai_ci'],
        text=True, timeout=60).strip()
    try:
        verify(container)
    finally:
        subprocess.run(['docker', 'stop', container], check=True, stdout=subprocess.DEVNULL, timeout=30)
