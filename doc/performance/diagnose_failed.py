#!/usr/bin/env python3
"""失败压测的只读诊断：保留原始审计失败，不将最终排空当成稳定通过。"""
import collections
import csv
import json
import math
import statistics
import sys
from pathlib import Path

def read_json(path):
    return json.loads(path.read_text())

def status(text):
    return dict(line.split('\t') for line in text.splitlines())

def inspect(directory):
    params = read_json(directory/'parameters.json')
    before, after = read_json(directory/'before.json'), read_json(directory/'after.json')
    with (directory/'samples.jtl').open() as f:
        rows = list(csv.DictReader(f))
    initial = min(int(row['timeStamp']) for row in rows)
    low = initial + params['warmup']*1000
    high = min(initial + params['duration']*1000,
               max(int(r['timeStamp'])+int(r['elapsed']) for r in rows)+1)
    measured = [r for r in rows if low <= int(r['timeStamp'])+int(r['elapsed']) < high]
    samples = [json.loads(line) for line in (directory/'resources.jsonl').open()]
    sampled = [s for s in samples if low <= s['app']['time'] < high]
    pending = [s for s in sampled if 'async' in s]
    base,end = pending[0]['time'], pending[-1]['time']
    first = [s['async']['grantPending'] for s in pending if s['time']<=base+60000]
    last  = [s['async']['grantPending'] for s in pending if s['time']>=end-60000]
    b,a=status(before['mysql']),status(after['mysql'])
    delta = {k:int(a[k])-int(v) for k,v in b.items() if k in a and v.isdecimal() and a[k].isdecimal()}
    previous = {(r[0],r[1]):r for r in csv.reader((directory/'sql-before.tsv').open(),delimiter='\t')}
    queries=[]
    for r in csv.reader((directory/'sql-after.tsv').open(),delimiter='\t'):
        old=previous.get((r[0],r[1]),['','','0','0','0','0','0'])
        n=int(r[2])-int(old[2])
        if n>0:
            queries.append({'sql':r[1], 'calls':n,
                            'totalSeconds':round(float(r[3])-float(old[3]),3),
                            'lockSeconds':round(float(r[4])-float(old[4]),3)})
    queries.sort(key=lambda row:row['totalSeconds'],reverse=True)
    codes = collections.Counter((r['responseCode'],r['businessCode']) for r in rows)
    errors=[{k:r[k] for k in ('timeStamp','responseCode','businessCode','requestId','uId','elapsed')}
            for r in rows if r['responseCode']!='200' or r['businessCode'] not in ('0000','D001')]
    audit=read_json(directory/'audit.json') if (directory/'audit.json').exists() else {}
    report={
        'test':directory.name, 'originalAuditPass':(directory/'summary.json').exists(),
        'errorCount':len(errors),'errorsFirst10':errors[:10],
        'totalHttp':len(rows),'httpByStatusAndBusinessCode':{f'{k[0]}:{k[1]}':v for k,v in codes.items()},
        'measuredSeconds':round((high-low)/1000,3),'measuredRps':round(len(measured)/((high-low)/1000),3),
        'mysqlRowLockWaits':delta.get('Innodb_row_lock_waits'),
        'mysqlRowLockWaitMs':delta.get('Innodb_row_lock_time'),
        'mysqlBufferPoolPhysicalReads':delta.get('Innodb_buffer_pool_reads'),
        'backlogPeak':max(s['async']['grantPending'] for s in pending),
        'backlogMeanGrowth':round(statistics.mean(last)-statistics.mean(first),3),
        'stockLagPeakSampled':max(s['rocketmq']['lottery-perf-stock'][1] for s in sampled if 'rocketmq' in s),
        'sqlTop':queries[:12],
        'originalAudit':{'takes':audit.get('takes'),'pending':audit.get('pending')},
        'drainSeconds':read_json(directory/'drain.json')['seconds'] if (directory/'drain.json').exists() else None,
    }
    (directory/'diagnostic-only.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(json.dumps({k:report[k] for k in ('test','originalAuditPass','errorCount','measuredRps','mysqlRowLockWaits','mysqlRowLockWaitMs','backlogPeak','backlogMeanGrowth','stockLagPeakSampled')},ensure_ascii=False))
if __name__=='__main__':
    for name in sys.argv[1:]:inspect(Path(name))
