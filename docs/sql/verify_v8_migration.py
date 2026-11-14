#!/usr/bin/env python3
"""在显式指定的隔离 MySQL 实例上验证 V8；创建独立 fixture 库，保留供复查。"""
import argparse
import re
import subprocess
from importlib import import_module
from pathlib import Path

BASE = Path(__file__).resolve().parent
v8 = import_module('V8__activity_prize')


def verify(mysql, suffix):
    source, target, fresh = ('lottery_v7_fixture_' + suffix, 'lottery_v8_upgraded_' + suffix,
                             'lottery_v8_fresh_' + suffix)

    def sql(statement, error=None):
        result = subprocess.run(mysql + ['--batch', '--skip-column-names', '--default-character-set=utf8mb4'],
                                input=statement, text=True, capture_output=True)
        if error:
            assert result.returncode and error in result.stdout + result.stderr, result
        elif result.returncode:
            raise RuntimeError(result.stderr.strip())
        return result.stdout.strip()

    def schema(path, name):
        return (BASE / path).read_text().replace('CREATE DATABASE lottery ', f'CREATE DATABASE {name} ').replace(
            'USE lottery;', f'USE {name};')

    sql(schema('lottery-v7-schema.sql', source))
    sql(f'USE {source};\n' + (BASE / 'v8-migration-fixture.sql').read_text())
    # 迁移前分别制造共享奖池、重复奖品和未排空的义务。冲突发生时目标库不能已经创建。
    for issue, create, remove in (
        ('shared_pool', 'INSERT INTO activity(activity_id,draw_config_id,state) VALUES (503,501,4)',
         'DELETE FROM activity WHERE activity_id=503'),
        ('duplicate_prize', "INSERT INTO draw_config_prize(id,draw_config_id,award_id,award_count,award_surplus_count,award_rate) VALUES (99,502,'fixture-prize',7,7,0)",
         'DELETE FROM draw_config_prize WHERE id=99'),
        ('undrained_order', 'UPDATE draw_order SET stock_settlement_event_status=4 WHERE order_id=91',
         'UPDATE draw_order SET stock_settlement_event_status=1 WHERE order_id=91'),
    ):
        sql(f'USE {source}; {create};')
        report = sql(v8.migration(source, target), issue)
        assert issue in report, report
        assert sql(f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='{target}';") == '0'
        sql(f'USE {source}; {remove};')
        print(f'冲突已阻断：{issue}')

    print(sql(v8.migration(source, target)))
    sql(schema('lottery-v8-schema.sql', fresh))
    sql((BASE / 'lottery-demo-seed.sql').read_text().replace('USE lottery;', f'USE {fresh};'))
    tables = sql(f'SHOW TABLES FROM {target};').splitlines()
    assert len(tables) == 7 and tables == sql(f'SHOW TABLES FROM {fresh};').splitlines()
    for table in tables:
        definitions = [re.sub(r' AUTO_INCREMENT=\d+', '', sql(f'SHOW CREATE TABLE {db}.{table};'))
                       for db in (target, fresh)]
        assert definitions[0] == definitions[1], table
    assert sql(f'SELECT activity_id,award_surplus_count FROM {target}.activity_prize ORDER BY activity_id;') == '501\t7\n502\t4'
    assert sql(f'SELECT order_id,activity_id FROM {target}.award_stock_settlement;') == '91\t502'
    assert sql(f"SELECT take_id,state FROM {target}.draw_participation WHERE request_id='historic-resume';") == '82\t0'
    assert sql(f'SELECT legacy_uuid,fulfilled_at,stock_attempt,fulfillment_attempt FROM {target}.draw_order;') == (
        'historic-91\t2026-10-01 01:00:00.123\t3\t4')
    assert sql(f'SELECT grant_type,scheduled_at FROM {source}.draw_order;') == '2\t2030-01-01 00:00:00'
    print('通过：新建/升级七表定义一致、历史身份/快照/库存/账本/代数保留、旧库未改动')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql', nargs='+', required=True, help='连接专用 MySQL 实例的可执行包装')
    parser.add_argument('--suffix', required=True, help='本次新建 fixture 库名后缀')
    args = parser.parse_args()
    if not re.fullmatch(r'[a-z0-9_]{1,25}', args.suffix):
        parser.error('后缀须由小写字母、数字或下划线组成')
    verify(args.mysql, args.suffix)
