#!/usr/bin/env python3
"""汇总 V8 同栈 A/B 数据，SQL 全轮增量和实际测量窗口分别保存。"""
import argparse
from collections import Counter
import csv
import json
import math
from pathlib import Path


def sql_snapshot(path):
    with path.open() as source:
        return {statement: (int(calls), float(seconds), float(lock), int(examined), int(affected))
                for _, statement, calls, seconds, lock, examined, affected in csv.reader(source, delimiter='\t')}


def group_statement(statement):
    if statement == 'COMMIT':
        return 'explicitCommit'
    if statement.startswith('UPDATE `draw_order`'):
        if 'SET `stock_attempt` =' in statement:
            return 'stockClaim'
        if 'SET `fulfillment_attempt` =' in statement:
            return 'grantClaim'
        if 'SET `award_fulfillment_status` =' in statement:
            return 'grantCas'
        if 'SET `stock_settlement_event_status` = ? , `update_time`' in statement:
            return 'stockCas'
        if 'SET `stock_settlement_event_status` = CASE' in statement or (
                'SET `stock_settlement_event_status` = ? , `stock_retry_at` = TIMESTAMPADD' in statement):
            return 'stockResult'
        if 'SET `fulfillment_event_status` = CASE' in statement or (
                'SET `fulfillment_event_status` = ? , `fulfillment_retry_at` = TIMESTAMPADD' in statement):
            return 'grantResult'
        return 'otherOrderUpdate'
    if statement.startswith('INSERT INTO `draw_order`'):
        return 'orderInsert'
    if statement.startswith('INSERT INTO `award_stock_settlement`'):
        return 'ledgerInsert'
    if statement.startswith('UPDATE `activity_prize`'):
        return 'stockDeduct'


def grouped_sql(before, after):
    values = {}
    for statement, end in after.items():
        key = group_statement(statement)
        if key is None:
            continue
        start = before.get(statement, (0,) * 5)
        delta = [b - a for a, b in zip(start, end)]
        dst = values.setdefault(key, Counter())
        for field, difference in zip(('calls', 'seconds', 'lockSeconds', 'examinedRows', 'affectedRows'), delta):
            dst[field] += difference
    return values


def commits(path):
    counts, buckets = {}, {}
    with path.open() as source:
        for key, value, detail in csv.reader(source, delimiter='\t'):
            if key.startswith('trx_'):
                assert detail == 'enabled', (path.name, key, detail)
                counts[key] = int(value)
            else:
                buckets[int(key)] = (int(value), int(detail))
    return counts, buckets


def commit_delta(before, after):
    start, first = commits(before)
    end, last = commits(after)
    # 分位数基于同直方图桶增量，不能从累计分位数相减。
    delta = [(boundary, n - first[key][1]) for key, (boundary, n) in sorted(last.items())]
    assert all(n >= 0 for _, n in delta), 'COMMIT 直方图计数被重置'
    total = sum(n for _, n in delta)
    assert total > 0, '未采集到 COMMIT'
    result = {key: n - start[key] for key, n in end.items()}
    for quantile in (.95, .99):
        rank = math.ceil(total * quantile)
        for boundary, count in delta:
            rank -= count
            if rank <= 0:
                result[f'mysqlCommitP{int(quantile * 100)}UpperMs'] = boundary / 1e9
                break
    result['histogramCommitCalls'] = total
    return result


def load(directory, filename):
    return json.loads((directory / filename).read_text())


def order_update(sql):
    normalized = ' '.join(sql.lower().replace('\\n', ' ').split())
    for clause, label in (
        ('set award_fulfillment_status', 'grantCas'),
        ('set fulfillment_event_status = case', 'grantSingleResult'),
        ('set fulfillment_event_status = 3', 'grantBatchResult'),
        ('set stock_settlement_event_status = case', 'stockSingleResult'),
        ('set stock_settlement_event_status = 3', 'stockBatchResult'),
        ('set stock_settlement_event_status = 1', 'stockCas'),
    ):
        if clause in normalized:
            return label
    return 'commit' if normalized == 'commit' else 'other'


def timestamp_checks(directory):
    # 同一订单的 MySQL INSERT 时间应早于 HTTP 响应，也应早于其结算 CAS。
    with (directory / 'samples.jtl').open() as source:
        response_times = {row['orderId']: int(row['timeStamp']) + int(row['elapsed'])
                          for row in csv.DictReader(source) if row['businessCode'] == '0000'}
    after_http = settled_before_insert = largest_skew = 0
    with (directory / 'orders.tsv').open() as source:
        for order in csv.DictReader(source, delimiter='\t'):
            created = int(order['createdMs'])
            skew = created - response_times[order['order']]
            after_http += skew > 0
            settled_before_insert += int(order['stockDoneMs']) < created
            largest_skew = max(largest_skew, skew)
    return {'createdAfterHttpResponse': after_http, 'stockDoneBeforeOrderCreate': settled_before_insert,
            'maxCreatedAfterHttpMs': largest_skew}


def summarize(directory):
    name = directory.name
    summary = load(directory, 'summary.json')
    stable = load(directory, 'stability.json')
    diagnostics = load(directory, 'v8-diagnostics.json')[name]
    sql = grouped_sql(sql_snapshot(directory / 'sql-before.tsv'), sql_snapshot(directory / 'sql-after.tsv'))
    commit_after = directory.parent / f'{name}-commits-after.tsv'
    # 已留存的完整业务轮次可能缺独立的事务快照，此时只报告可复核的 SQL 与业务数据。
    commits_stats = commit_delta(directory.parent / f'{name}-commits-before.tsv',
                                 commit_after) if commit_after.is_file() else None
    # 各资源采样可能重复看到同一个等待。全局锁等待时间额外给出精确累计增量。
    edges = Counter()
    for edge in diagnostics['lockEdges']:
        edges[edge['table']] += edge['samples']
    resources = [json.loads(line) for line in (directory / 'resources.jsonl').read_text().splitlines()]
    start, end = diagnostics['mysqlKafkaSampleWindowMs']
    first, last = (next(sample for sample in resources if sample['time'] == stamp) for stamp in (start, end))
    low, high = diagnostics['measureWindowMs']
    order_edges = Counter()
    for sample in resources:
        if low <= sample['app']['time'] < high:
            for row in sample['lockWaits'].splitlines():
                fields = row.split('\t')
                if fields[1] == 'draw_order':
                    order_edges[(order_update(fields[11]), order_update(fields[15]), fields[14])] += 1
    # 从两个消费组的真实位点差分计算提交速率。
    all_rps = {group: (last['kafka'][group][0] - values[0]) / ((end - start) / 1000)
               for group, values in first['kafka'].items()}
    return {
        'stable': stable['stable'],
        'failedStabilityChecks': [key for key, passed in stable['checks'].items() if not passed],
        'windowSeconds': summary['windowSeconds'],
        'orders': summary['ordersTotal'],
        'audit': summary['audit'],
        'httpRps': summary['newDrawRps'],
        'stockRps': summary['settlementRps'],
        'grantRps': summary['fulfillmentRps'],
        'httpP95Ms': summary['p95Ms'],
        'httpP99Ms': summary['p99Ms'],
        'httpErrorRate': summary['httpErrorRate'],
        'systemErrorRate': summary['systemErrorRate'],
        'stockP95UpperMs': summary['stockLatencyBoundsMs']['0.95'][1],
        'timestampChecks': timestamp_checks(directory),
        'grantBacklogGrowth': stable['backlogMeanGrowth'],
        'grantBacklogPeak': stable['backlogPeak'],
        'stockBacklogFirstLast60s': diagnostics['stockPendingFirstLast60sMean'],
        'drainSeconds': summary['drainSeconds'],
        'mysqlCommitMeanMs': diagnostics['mysqlDigest']['COMMIT']['meanMs'],
        'tx3Commit': diagnostics['jdbcCommitsByTransaction']['jdbcCommit/TX3'],
        'mysqlCommits': commits_stats,
        'fullRunSql': sql,
        'sampledLockEdges': edges,
        'orderLockPairs': [{'waiting': waiter, 'blocking': blocker, 'blockingState': state, 'samples': count}
                           for (waiter, blocker, state), count in order_edges.most_common()],
        'globalLockWaits': summary['mysqlDelta']['Innodb_row_lock_waits'],
        'globalLockWaitMs': summary['mysqlDelta']['Innodb_row_lock_time'],
        'hikari': diagnostics['hikari'],
        'kafkaPeakLag': summary['peakSampledKafkaLag'],
        'kafkaCommittedPerSecond': all_rps,
        'swapMiB': diagnostics['swapMiB'],
        'mysqlCpuPercentOfOneCore': diagnostics['mysqlCpuMeanPercentOfOneCore'],
        'mysqlIo': diagnostics['mysqlFileIo'],
        'outbox': summary['outbox'],
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directories', type=Path, nargs='+')
    args = parser.parse_args()
    print(json.dumps({path.name: summarize(path) for path in args.directories}, ensure_ascii=False, indent=2))
