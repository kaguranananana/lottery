#!/usr/bin/env python3
"""按事先固定的本地完整业务链路阈值判断持续测试，列出未通过的具体条件。"""
import json
from pathlib import Path
import statistics
import sys

def check(directory):
    p=json.loads((directory/'parameters.json').read_text())
    r=json.loads((directory/'summary.json').read_text())
    rate=r['newDrawRps'];target=p.get('target_rps',0)
    backlog=r['backlog'];base=backlog[0][0];end=backlog[-1][0]
    first=[s['grantPending'] for t,s in backlog if t<=base+60000]
    last=[s['grantPending'] for t,s in backlog if t>=end-60000]
    growth=statistics.mean(last)-statistics.mean(first)
    peak=max(s['grantPending'] for _,s in backlog)
    checks={
        '至少180秒测量':r['windowSeconds']>=180,
        '达到目标到达速率95%':target>0 and rate>=target*.95,
        'HTTP与系统错误率低于0.1%':r['httpErrorRate']<.001 and r['systemErrorRate']<.001,
        'HTTP P99低于1秒':r['p99Ms']<1000,
        '积压峰值不超过10秒请求量':peak<=rate*10,
        '首尾平均积压增长不超过1秒请求量':growth<=rate,
        '异步P95保守上界低于10秒':r['asyncP95UpperMs']<10000,
        '入口结束后15秒内收敛':r['drainSeconds']<=15,
        '业务审计通过':r['audit']=='PASS',
    }
    result={'stable':all(checks.values()),'checks':checks,'measuredRps':rate,'targetRps':target,'backlogPeak':peak,'backlogMeanGrowth':growth,'firstMeanBacklog':statistics.mean(first),'lastMeanBacklog':statistics.mean(last)}
    (directory/'stability.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(directory.name,json.dumps(result,ensure_ascii=False))
    return result

if __name__=='__main__':
    for name in sys.argv[1:]:check(Path(name))
