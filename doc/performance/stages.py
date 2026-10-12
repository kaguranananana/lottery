#!/usr/bin/env python3
"""汇总业务包含式计时、实际锁现场和宿主机换页增量；不混同 CPU 与等待时间。"""
import collections
import json
from pathlib import Path
import re
import sys

def summarize(directory):
    before=json.loads((directory/'before.json').read_text())['app']['timing']
    after=json.loads((directory/'after.json').read_text())['app']['timing']
    stages={}
    for key,end in after.items():
        start=before.get(key,[0,0,0,[0]*20])
        count=end[0]-start[0]
        if not count:continue
        buckets=[e-s for e,s in zip(end[3],start[3])]
        cumulative=0;upper=None
        for i,n in enumerate(buckets):
            cumulative+=n
            if cumulative>=count*.95:
                # 最后一桶是溢出桶，不声称它有有限的上界。
                upper=2**i/1000 if i<19 else None
                break
        stages[key]={'count':count,'totalMs':(end[1]-start[1])/1e6,'meanMs':(end[1]-start[1])/count/1e6,'errors':end[2]-start[2],'p95BucketUpperMs':upper}
    samples=[json.loads(line) for line in (directory/'resources.jsonl').read_text().splitlines()]
    locks=collections.Counter()
    for sample in samples:
        for line in sample.get('lockWaits','').splitlines():
            fields=line.split('\t')
            if len(fields)>=6:locks[tuple(fields[:6])]+=1
    vm=[{k:int(v) for k,v in re.findall(r'(Swapins|Swapouts):\s*(\d+)\.',s['hostVm'])} for s in samples]
    result={'stages':stages,'lockObservations':[{'lock':list(key),'observations':n} for key,n in locks.most_common()],
            'hostSwapPagesDelta':{k:vm[-1][k]-vm[0][k] for k in vm[0]},'hostPageBytes':16384,
            'samplingSeconds':(samples[-1]['time']-samples[0]['time'])/1000}
    (directory/'stage-summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(directory.name,'阶段计时与锁现场已汇总',result['hostSwapPagesDelta'])
    return result

if __name__=='__main__':
    for name in sys.argv[1:]:summarize(Path(name))
