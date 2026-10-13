#!/usr/bin/env python3
"""在已启动的专用压测 MySQL 上读取体量、扫描执行计划及持久化事实。"""
import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from environment import ROOT, sql, verify
from run import mysql_metrics, write


def summarize_plans(directory: Path) -> dict:
    """从本次 MySQL 8 执行树提取实际行数和服务端耗时，原始计划保持原样。"""
    summary = {}
    for name in ('scanInvoiceMqState', 'scanAwardStockMqState'):
        runs = []
        for repeat in (1, 2, 3):
            tables = []
            for path in sorted(directory.glob(f'*-{name}-analyze-{repeat}.txt')):
                # mysql 批处理将执行树内换行转义；只在汇总展示时还原。
                tree = path.read_text().replace('\\n', '\n')
                # 按执行树顺序提取起止耗时、每轮行数与循环次数，首项为根节点。
                stages = re.findall(r'actual time=([\d.]+)\.\.([\d.]+) rows=(\d+) loops=(\d+)', tree)
                # 本次计划最深层是索引迭代器；行数包含其循环次数，不当作 SQL digest 统计。
                tables.append({'file': path.name, 'rootMs': float(stages[0][1]),
                    'returnedRows': int(stages[0][2]),
                    'scanRows': int(stages[-1][2]) * int(stages[-1][3]), 'plan': tree})
            runs.append({'repeat': repeat, 'totalRootMs': sum(item['rootMs'] for item in tables),
                'totalScanRows': sum(item['scanRows'] for item in tables),
                'totalReturnedRows': sum(item['returnedRows'] for item in tables), 'tables': tables})
        summary[name] = runs
    write(directory / 'plan-summary.json', summary)
    return summary


def diagnose(directory: Path) -> dict:
    # 身份校验复用既有工具；本脚本没有启动、迁移、删除或重置入口。
    verify()
    directory.mkdir(parents=True, exist_ok=False)
    mapper = ET.parse(ROOT / 'lottery-interfaces/src/main/resources/mybatis/mapper/UserStrategyExport_Mapper.xml').getroot()
    queries = {}
    for name, limit in (('scanInvoiceMqState', 100), ('scanAwardStockMqState', 200)):
        # 直接提取生产 Mapper 的 SQL，避免手工复制导致诊断条件与业务不一致。
        element = mapper.find(f"select[@id='{name}']")
        queries[name] = ' '.join(''.join(element.itertext()).split()).replace('#{limit}', str(limit))
    tables = [f'lottery_{db:02d}.user_strategy_export_{table:03d}'
              for db in (1, 2) for table in range(4)]
    write(directory / 'metrics-before.json', {'mysql': mysql_metrics(),
        'lockWaits': sql('SELECT * FROM performance_schema.data_lock_waits;')})
    counts = ' UNION ALL '.join(f"SELECT '{table}',COUNT(*) FROM {table}" for table in tables)
    # 精确 COUNT 与 information_schema 估算分开记录，不用估算值冒充初始业务量。
    data = {
        'orderRowsByShard': sql(counts + ';').strip(),
        'takeAndQuotaRows': sql(' UNION ALL '.join(
            f"SELECT '{schema}.{table}',COUNT(*) FROM {schema}.{table}"
            for schema in ('lottery_01', 'lottery_02')
            for table in ('user_take_activity', 'user_take_activity_count')) + ';').strip(),
        'globalRows': sql(' UNION ALL '.join(
            f"SELECT '{table}',COUNT(*) FROM lottery.{table}"
            for table in ('activity', 'strategy_detail', 'award_stock_settlement')) + ';').strip(),
        'orderStates': sql(' UNION ALL '.join(
            f"SELECT '{table}',stock_mq_state,mq_state,grant_state,COUNT(*) FROM {table} GROUP BY stock_mq_state,mq_state,grant_state"
            for table in tables) + ';').strip(),
        'tableSizes': sql("SELECT table_schema,table_name,table_rows,data_length,index_length FROM information_schema.tables WHERE table_schema IN ('lottery','lottery_01','lottery_02') ORDER BY table_schema,table_name;").strip(),
        'parameters': sql('SELECT VERSION(),@@innodb_flush_log_at_trx_commit,@@sync_binlog,@@transaction_isolation,@@innodb_buffer_pool_size;').strip(),
    }
    write(directory / 'initial-data.json', data)
    (directory / 'indexes.tsv').write_text(sql("SELECT table_schema,table_name,index_name,seq_in_index,column_name FROM information_schema.statistics WHERE table_schema IN ('lottery','lottery_01','lottery_02') ORDER BY table_schema,table_name,index_name,seq_in_index;"))
    for table in tables:
        for name, query in queries.items():
            # 物理表来自既定两库四表；保留实际执行 SQL 及计划供重新运行核对。
            statement = query.replace('FROM user_strategy_export', f'FROM {table}')
            stem = table.replace('.', '-') + '-' + name
            (directory / (stem + '.sql')).write_text(statement + ';\n')
            (directory / (stem + '-explain.tsv')).write_text(sql('EXPLAIN ' + statement + ';'))
            # ANALYZE 执行真实只读查询；三次记录用于观察缓存，不能当高负载 A/B。
            for repeat in range(1, 4):
                (directory / (stem + f'-analyze-{repeat}.txt')).write_text(sql('EXPLAIN ANALYZE ' + statement + ';'))
    summarize_plans(directory)
    union = ' UNION ALL '.join(f'SELECT * FROM {table}' for table in tables)
    takes = ' UNION ALL '.join(f'SELECT u_id,activity_id,take_id,state FROM lottery_{db:02d}.user_take_activity' for db in (1, 2))
    # 这里只核对 MySQL 持久化事实；Redis 与 Kafka 未启动，不宣称完整业务审计通过。
    audit = {
        'scope': '全部现存 lottery/lottery_01/lottery_02 MySQL 数据；不含 Redis/Kafka 审计',
        'orders': sql(f'SELECT COUNT(*),COUNT(DISTINCT order_id),COUNT(DISTINCT take_id),SUM(stock_mq_state<>1),SUM(mq_state<>1 OR grant_state<>1),SUM(stock_mq_state=4) FROM ({union}) o;').strip(),
        'takes': sql(f'SELECT COUNT(*),SUM(state=0),COUNT(*)-COUNT(DISTINCT take_id) FROM ({takes}) t;').strip(),
        'ledger': sql('SELECT COUNT(*),COUNT(DISTINCT order_id) FROM lottery.award_stock_settlement;').strip(),
        'ledgerMismatch': sql(f'SELECT COUNT(*) FROM ({union}) o LEFT JOIN lottery.award_stock_settlement l ON o.order_id=l.order_id WHERE l.order_id IS NULL OR o.u_id<>l.u_id OR o.award_id<>l.award_id OR o.strategy_id<>l.strategy_id;').strip(),
        'takeMismatch': sql(f'SELECT COUNT(*) FROM ({union}) o LEFT JOIN ({takes}) t ON o.take_id=t.take_id WHERE t.take_id IS NULL OR o.u_id<>t.u_id OR o.activity_id<>t.activity_id OR t.state<>1;').strip(),
        'negativeStock': sql('SELECT COUNT(*) FROM lottery.strategy_detail WHERE award_surplus_count<0;').strip(),
        'quota': sql(' UNION ALL '.join(f'SELECT {db},SUM(total_count-left_count),SUM(left_count<0) FROM lottery_{db:02d}.user_take_activity_count' for db in (1, 2)) + ';').strip(),
        'ordersUnchangedDuringDiagnosis': sql(counts + ';').strip() == data['orderRowsByShard'],
    }
    write(directory / 'mysql-audit.json', audit)
    write(directory / 'metrics-after.json', {'mysql': mysql_metrics(),
        'lockWaits': sql('SELECT * FROM performance_schema.data_lock_waits;')})
    return audit


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
    print(diagnose(args.directory))
