#!/usr/bin/env python3
"""按完成时间计算稳定窗口吞吐，并通过关联 ID 与账本核对真实业务完成。"""
import collections
import csv
import json
import math
from pathlib import Path
import sys

BASE=Path(__file__).resolve().parent

def quantile(values,q):
    # 使用最近秩，分位数定义固定，避免不同工具插值导致无法复现。
    ordered=sorted(values)
    return ordered[max(0,math.ceil(q*len(ordered))-1)]

def read_table(path):
    with path.open() as f:return list(csv.DictReader(f,delimiter='\t'))

def analyze(directory):
    params=json.loads((directory/'parameters.json').read_text())
    with (directory/'samples.jtl').open() as f: rows=list(csv.DictReader(f))
    assert rows,'未产生 HTTP 样本'
    start=min(int(row['timeStamp']) for row in rows)
    low=start+params['warmup']*1000
    high=start+params['duration']*1000
    # 采用实际完成时间落在窗口内的请求，排除预热；排队请求不能提前计入RPS。
    measured=[r for r in rows if low<=int(r['timeStamp'])+int(r['elapsed'])<high]
    observed_threads=collections.Counter(int(r['allThreads']) for r in measured).most_common(1)[0][0]
    assert observed_threads==params['threads'], '实际稳定线程数与计划不符，不能计入并发阶梯'
    seconds=(high-low)/1000
    latencies=[int(r['elapsed']) for r in measured]
    business=collections.Counter(r['businessCode'] for r in measured)
    success=[r for r in measured if r['responseCode']=='200' and r['businessCode'] in ('0000','D001')]
    orders=read_table(directory/'orders.tsv');takes=read_table(directory/'takes.tsv')
    audit=json.loads((directory/'audit.json').read_text())
    # requestId、用户和 takeID 逐项关联，幂等回放不计作新的抽奖。
    by_request={(t['user'],t['request']):t for t in takes}
    by_take={o['take']:o for o in orders}
    assert len(by_request)==len(takes),'出现重复逻辑抽奖请求'
    assert len(by_take)==len(orders),'同一参与产生重复订单'
    assert len({o['order'] for o in orders})==len(orders),'出现全局重复订单'
    complete=[r for r in rows if r['responseCode']=='200' and r['businessCode'] in ('0000','D001')]
    assert len({(r['uId'],r['requestId']) for r in complete})==len(complete),'独立基准包含重复请求'
    assert len(complete)==len(takes),'成功的新抽奖操作数与参与记录不符'
    for r in complete:
        take=by_request[(r['uId'],r['requestId'])]
        assert take['take']==r['takeId'] and take['state']=='1','参与ID或消费状态不符'
        if r['businessCode']=='0000':
            order=by_take[r['takeId']]
            assert order['order']==r['orderId'],'HTTP 返回订单与数据库订单不符'
            assert (order['stockState'],order['mqState'],order['grantState'])==('1','1','1'),'订单最终状态未完成'
        else:
            assert r['takeId'] not in by_take,'未中奖参与有意外订单'
    assert len(orders)==sum(r['businessCode']=='0000' for r in complete)
    ledger_rows=read_table(directory/'ledger.tsv')
    assert {(o['order'],o['user'],o['award']) for o in orders}=={(o['order'],o['user'],o['award']) for o in ledger_rows},'订单与账本关联ID不符'
    assert len(ledger_rows)==len(orders),'出现重复账本'
    quota=sum(int(line.split('\t')[2]) for line in audit['quota'].splitlines())
    assert quota==len(takes),'次数扣减与参与记录不符'
    assert params['stock']-int(audit['redisActivity'])==len(takes),'活动库存与参与数不符'
    ledger=dict((a,int(n)) for a,n in (line.split('\t') for line in audit['ledger'].splitlines()))
    order_counts=collections.Counter(o['award'] for o in orders)
    for line in audit['stock'].splitlines():
        award,initial,left=line.split('\t')
        assert int(left)>=0 and int(audit['redisAwards'][award])>=0,'奖品超卖'
        # 创建接口当前未持久化 award_count；扣减基线采用明确的 fixture 配置，报告保留 NULL 缺陷。
        seeded=params['stock'] if params['award_stock']<0 else params['award_stock']
        assert seeded-int(left)==ledger.get(award,0)==order_counts[award],'奖品数据库、账本和订单不符'
        assert int(left)==int(audit['redisAwards'][award]),'奖品Redis与MySQL未收敛'
    if (directory/'initial-stock.tsv').exists():
        # 初始化快照可直接证明起始库存，后续对账不能只依赖计划中的数字。
        for line in (directory/'initial-stock.tsv').read_text().splitlines():
            award,total,left=line.split('\t')
            assert int(left)==seeded,'真实初始化库存与本轮fixture不符'
    assert audit['pending']['blocked']==audit['pending']['stockPending']==audit['pending']['grantPending']==0
    assert all(group[1]==0 and group[2]==8 for group in audit['kafka'].values()),'Kafka最终Lag未归零或分区位点不全'
    # 数据库 datetime 精度为秒：完成延迟给上下界，禁止把取整值冒充毫秒精度实测。
    bounds=[]
    for row in success:
        if row['businessCode']=='0000':
            done=int(by_take[row['takeId']]['doneMs'])
            ended=int(row['timeStamp'])+int(row['elapsed'])
            bounds.append((max(0,done-ended),max(0,done+1000-ended)))
    resources=[json.loads(line) for line in (directory/'resources.jsonl').read_text().splitlines()]
    samples=[s for s in resources if low<=s['app']['time']<high]
    cpu=[]
    for a,b in zip(samples,samples[1:]):
        cpu.append(100*(b['app']['processCpuTimeNs']-a['app']['processCpuTimeNs'])/((b['app']['time']-a['app']['time'])*1000000))
    stats={}
    for name in samples[0]['app']['pools']:
        first=samples[0]['app']['pools'][name];last=samples[-1]['app']['pools'][name]
        n=last['acquires']-first['acquires']
        stats[name]={'url':first['url'].split('/')[3].split('?')[0],'maxActive':max(s['app']['pools'][name]['active'] for s in samples),'maxWaiting':max(s['app']['pools'][name]['waiting'] for s in samples),'acquireMeanMs':(last['acquireNanos']-first['acquireNanos'])/n/1000000 if n else None,'timeouts':last['timeouts']-first['timeouts']}
    gctime=sum(x[2] for x in samples[-1]['app']['gc'])-sum(x[2] for x in samples[0]['app']['gc'])
    pending=[s for s in samples if 'async' in s]
    # 六个等长窗口用于检查吞吐随时间是否持续退化。
    buckets=[]
    for i in range(6):
        l=low+i*(high-low)/6;h=low+(i+1)*(high-low)/6
        part=[r for r in measured if l<=int(r['timeStamp'])+int(r['elapsed'])<h]
        buckets.append(len(part)/(seconds/6))
    result={'name':directory.name,'threads':params['threads'],'windowSeconds':seconds,'totalSamples':len(rows),'measuredSamples':len(measured),'rps':len(measured)/seconds,'newDrawRps':len(success)/seconds,'newDrawsWindow':len(success),'newDrawsTotal':len(takes),'ordersTotal':len(orders),'meanMs':sum(latencies)/len(latencies),'p50Ms':quantile(latencies,.5),'p95Ms':quantile(latencies,.95),'p99Ms':quantile(latencies,.99),'maxMs':max(latencies),'httpErrorRate':sum(r['responseCode']!='200' for r in measured)/len(measured),'systemErrorRate':sum(r['businessCode'] not in ('0000','D001','D004','D006','D007') or r['responseCode']!='200' for r in measured)/len(measured),'businessCodes':business,'cpuMeanPercentOfOneCore':sum(cpu)/len(cpu) if cpu else None,'heapMaxObservedMiB':max(s['app']['heapUsed'] for s in samples)/1048576,'gcMs':gctime,'gcPauseRatio':gctime/(samples[-1]['app']['time']-samples[0]['app']['time']),'pools':stats,'tomcatMaxBusy':max(v[0] for s in samples for v in s['app']['tomcat'].values()),'backlog':[(s['time'],s['async']) for s in pending],'rpsBuckets':buckets,'drainSeconds':json.loads((directory/'drain.json').read_text())['seconds'],'asyncP95LowerMs':quantile([b[0] for b in bounds],.95) if bounds else None,'asyncP95UpperMs':quantile([b[1] for b in bounds],.95) if bounds else None,'audit':'PASS'}
    result['observedThreads']=observed_threads
    (directory/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    return result

if __name__=='__main__':
    for name in sys.argv[1:]:
        r=analyze(Path(name)); print(json.dumps({k:r[k] for k in ('name','rps','p95Ms','p99Ms','ordersTotal','drainSeconds','audit')},ensure_ascii=False))
