#!/usr/bin/env python3
"""从原始采样和 SQL 计数器生成资源汇总，保留可核验的诊断增量。"""
import csv
import json
from pathlib import Path
import statistics
import sys

def summarize(directory):
    params=json.loads((directory/'parameters.json').read_text())
    result=json.loads((directory/'summary.json').read_text())
    with (directory/'samples.jtl').open() as f:rows=list(csv.DictReader(f))
    low=min(int(r['timeStamp']) for r in rows)+params['warmup']*1000
    high=low+result['windowSeconds']*1000
    samples=[json.loads(l) for l in (directory/'resources.jsonl').read_text().splitlines()]
    measured=[s for s in samples if low<=s['app']['time']<high]
    resources={}
    for container in ('mysql','redis','kafka'):
        # Docker百分比以单核100%表示，与应用累计CPU时间统一口径。
        values=[json.loads(v) for sample in measured for v in sample['docker'] if json.loads(v)['Name']==f'lottery-perf-{container}-1']
        resources[container]={'cpuMean':statistics.mean(float(x['CPUPerc'][:-1]) for x in values),'cpuMax':max(float(x['CPUPerc'][:-1]) for x in values),'memoryPeak':max(values,key=lambda x:float(x['MemPerc'][:-1]))['MemUsage']}
    statuses=[dict(line.split('\t') for line in s['mysql'].strip().splitlines()) for s in measured if 'mysql' in s]
    resources['mysqlStatuses']={'maxConnections':max(int(s['Threads_connected']) for s in statuses),'maxRunning':max(int(s['Threads_running']) for s in statuses),'rowLockWaitsDelta':int(statuses[-1]['Innodb_row_lock_waits'])-int(statuses[0]['Innodb_row_lock_waits']),'rowLockTimeMsDelta':int(statuses[-1]['Innodb_row_lock_time'])-int(statuses[0]['Innodb_row_lock_time'])}
    resources['kafkaLag']={'maxStockLag':max(s['kafka']['lottery-perf-stock'][1] for s in measured if 'kafka' in s),'maxInvoiceLag':max(s['kafka']['lottery-perf-invoice'][1] for s in measured if 'kafka' in s)}
    # 消费速率基于真实已提交位点增量，可能包含at-least-once重放，不能当成独立订单数。
    kafka_samples=[s for s in measured if 'kafka' in s]
    interval=(kafka_samples[-1]['time']-kafka_samples[0]['time'])/1000
    resources['kafkaCommittedPerSecond']={group:(kafka_samples[-1]['kafka'][group][0]-kafka_samples[0]['kafka'][group][0])/interval for group in ('lottery-perf-stock','lottery-perf-invoice')}
    resources['gcCountDelta']={end[0]:end[1]-start[1] for start,end in zip(measured[0]['app']['gc'],measured[-1]['app']['gc'])}
    resources['collectionMaxMs']=max(s['collectionMs'] for s in measured)
    (directory/'resource-summary.json').write_text(json.dumps(resources,indent=2,ensure_ascii=False))
    # 相同SQL在不同schema的计数器必须独立相减。
    before={(r[0],r[1]):r for r in csv.reader((directory/'sql-before.tsv').open(),delimiter='\t')}
    changes=[]
    for row in csv.reader((directory/'sql-after.tsv').open(),delimiter='\t'):
        old=before.get((row[0],row[1]),['','','0','0','0','0','0'])
        count=int(row[2])-int(old[2]);wait=float(row[3])-float(old[3])
        changes.append({'schema':row[0],'sql':row[1],'baselinePresent':(row[0],row[1]) in before,'count':count,'waitSeconds':wait,'meanMs':1000*wait/count if count else None,'lockSeconds':float(row[4])-float(old[4]),'rowsExamined':int(row[5])-int(old[5])})
    changes.sort(key=lambda r:r['waitSeconds'],reverse=True)
    (directory/'sql-delta.json').write_text(json.dumps(changes,indent=2,ensure_ascii=False))
    return resources

if __name__=='__main__':
    for arg in sys.argv[1:]:
        directory=Path(arg);summarize(directory);print(directory.name+' 资源与SQL增量已汇总')
