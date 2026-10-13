#!/usr/bin/env python3
"""在相同八表数据上交替比较补偿索引访问路径；不改业务数据或生产 SQL。"""
import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from environment import ROOT, sql, verify
from preflight import sample
from run import write


def compare(directory: Path, repeats: int) -> dict:
    # 复用专用实例身份校验，新目录确保历史证据不覆盖。
    verify()
    directory.mkdir(parents=True, exist_ok=False)
    mapper = ET.parse(ROOT / 'lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml').getroot()
    # 谓词和投影直接读取当前生产 Mapper，只替换分片表和批次上限。
    query = ' '.join(''.join(mapper.find("select[@id='scanInvoiceMqState']").itertext()).split()).replace('#{limit}', '100')
    tables = [f'lottery_{db:02d}.user_strategy_export_{tb:03d}' for db in (1, 2) for tb in range(4)]
    count_sql = ' UNION ALL '.join(f"SELECT '{table}',COUNT(*) FROM {table}" for table in tables) + ';'
    # 状态分布与行数一起核对，不能仅凭 COUNT 相同宣称消息事实未变化。
    state_sql = ' UNION ALL '.join(f"SELECT '{table}',stock_mq_state,mq_state,grant_state,COUNT(*)"
                                  f" FROM {table} GROUP BY stock_mq_state,mq_state,grant_state" for table in tables) + ';'
    before = sql(count_sql)
    before_states = sql(state_sql)
    # 保存局部实验的原始换页端点；采样位于对照循环外，不影响某一路径的执行计时。
    host_before = sample()
    write(directory / 'host-before.json', host_before)
    indexes = sql("SELECT table_schema,table_name,column_name FROM information_schema.statistics"
                  " WHERE table_schema IN ('lottery_01','lottery_02') AND index_name='idx_invoice_grant_state'"
                  " ORDER BY table_schema,table_name,seq_in_index;")
    # 本实验只排除一个明确索引，必须先证明八表均为所测单列定义。
    assert indexes.splitlines() == [table.replace('.', '\t') + '\tgrant_state' for table in tables], indexes
    write(directory / 'initial-data.json', {'orderRowsByShard': before.strip(), 'orderStates': before_states.strip(), 'indexes': indexes.strip(),
        'parameters': sql('SELECT VERSION(),@@innodb_flush_log_at_trx_commit,@@sync_binlog,@@innodb_buffer_pool_size;').strip()})
    runs = []
    for repeat in range(repeats):
        results = {'withoutIndex': [], 'withIndex': []}
        for table in tables:
            # 每次反转先后顺序，减少固定执行顺序及缓存升温的偏差。
            order = ('withoutIndex', 'withIndex') if repeat % 2 == 0 else ('withIndex', 'withoutIndex')
            for variant in order:
                target = table
                if variant == 'withoutIndex':
                    # 仅诊断查询忽略本次新索引，恢复原访问路径；数据和原条件完全相同。
                    target += ' IGNORE INDEX (idx_invoice_grant_state)'
                statement = query.replace('FROM user_strategy_export', f'FROM {target}')
                stem = f'{repeat + 1}-{table.replace(".", "-")}-{variant}'
                (directory / f'{stem}.sql').write_text(statement + ';\n')
                raw = sql('EXPLAIN ANALYZE ' + statement + ';')
                (directory / f'{stem}.txt').write_text(raw)
                # MySQL 批处理转义换行，汇总时还原；首节点是根，末节点是底层迭代器。
                tree = raw.replace('\\n', '\n')
                stages = re.findall(r'actual time=([\d.]+)\.\.([\d.]+) rows=(\d+) loops=(\d+)', tree)
                assert stages, tree
                results[variant].append({'table': table, 'file': stem + '.txt', 'rootMs': float(stages[0][1]),
                    'returnedRows': int(stages[0][2]), 'scanRows': int(stages[-1][2]) * int(stages[-1][3]), 'plan': tree})
        runs.append({'repeat': repeat + 1, 'variants': {name: {'tables': rows,
            'totalRootMs': sum(row['rootMs'] for row in rows),
            'totalScanRows': sum(row['scanRows'] for row in rows),
            'totalReturnedRows': sum(row['returnedRows'] for row in rows)} for name, rows in results.items()}})
    # 必须保持同一份数据；这里不认证业务审计、容量或完整链路提升倍数。
    after = sql(count_sql)
    after_states = sql(state_sql)
    host_after = sample()
    write(directory / 'host-after.json', host_after)
    result = {'dataUnchanged': after == before and after_states == before_states,
        'afterOrderRowsByShard': after.strip(), 'afterOrderStates': after_states.strip(), 'runs': runs,
        'hostSwapMiB': {key: (host_after['pages'][field] - host_before['pages'][field]) * host_after['pageBytes'] / 1048576
                        for key, field in (('in', 'Swapins'), ('out', 'Swapouts'))},
        'scope': '同数据生产补偿谓词的索引访问路径，八表顺序执行的根节点耗时合计'}
    write(directory / 'comparison.json', result)
    assert result['dataUnchanged'], '比较期间数据量改变，不能认证同数据对照'
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--repeats', type=int, default=6)
    args = parser.parse_args()
    if args.repeats <= 0:
        parser.error('重复次数必须大于零')
    result = compare(args.directory, args.repeats)
    print({variant: [run['variants'][variant]['totalScanRows'] for run in result['runs']]
           for variant in ('withoutIndex', 'withIndex')})
