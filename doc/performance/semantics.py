#!/usr/bin/env python3
"""真实 HTTP 验证热点请求、重放、客户端超时和未完成请求归属。"""
import argparse
import collections
import concurrent.futures
import csv
import http.client
import json
from pathlib import Path
import socket
import threading
import time
from run import http as control,write,audit,snapshot,BASE

def draw(user,id,request):
    connection=http.client.HTTPConnection('127.0.0.1',18090,timeout=15)
    started=time.time()
    try:
        connection.request('POST','/api/v1/draw',json.dumps({'userId':user,'activityId':id,'requestId':request}),{'Content-Type':'application/json'})
        response=connection.getresponse()
        result=json.loads(response.read())
        return {'user':user,'request':request,'startedMs':int(started*1000),'endedMs':int(time.time()*1000),'http':response.status,'result':result}
    finally:
        connection.close()

def burst(user,id,same):
    barrier=threading.Barrier(50)
    def invoke(i):
        barrier.wait(timeout=10)
        return draw(user,id,'same-request' if same else 'hot-'+str(i))
    with concurrent.futures.ThreadPoolExecutor(max_workers=50) as executor:
        return list(executor.map(invoke,range(50)))

def main(session, name, id):
    directory=BASE/'results'/session/name;directory.mkdir(exist_ok=False)
    write(directory/'setup.json',control('/__perf/setup',dict(id=id,stock=10000,quota=100,users=0,prefix='unused')))
    diff=burst('hot-different',id,False)
    same=burst('hot-same',id,True)
    # 全部同ID成功回复必须关联同一个参与和订单；D006是预期并发拒绝。
    assert all(r['http']==200 and r['result']['code'] in ('0000','D006') for r in diff+same)
    replay=draw('hot-same',id,'same-request')
    same_success=[r for r in same+[replay] if r['result']['code']=='0000']
    assert len({r['result']['takeId'] for r in same_success})==1
    assert len({r['result']['orderId'] for r in same_success})==1
    assert len({r['result']['award']['awardId'] for r in same_success})==1
    # 请求体已发送，再把客户端读取超时设为 1 毫秒；只模拟客户端放弃等待，不停止中间件。
    connection=http.client.HTTPConnection('127.0.0.1',18090,timeout=15)
    connection.connect()
    connection.request('POST','/api/v1/draw',json.dumps({'userId':'timeout-user','activityId':id,'requestId':'timeout-original'}),{'Content-Type':'application/json'})
    connection.sock.settimeout(.001)
    timeout_result=None
    try:
        response=connection.getresponse();timeout_result=json.loads(response.read())
        raise AssertionError('本轮没有发生客户端读取超时，不能宣称覆盖了超时场景')
    except socket.timeout:
        timeout_observed=True
    finally:
        connection.close()
    attempts=[]
    deadline=time.time()+15
    while True:
        attempt=draw('timeout-user',id,'timeout-original');attempts.append(attempt)
        # D006要求继续原请求号，等待是接口契约中的恢复行为。
        if attempt['result']['code']=='0000':break
        assert attempt['result']['code']=='D006' and time.time()<deadline
        time.sleep(.05)
    retry=draw('timeout-user',id,'timeout-original')
    assert retry['result']['orderId']==attempts[-1]['result']['orderId']
    # 未完成机会经真实参与服务创建，新ID必须提示原ID，再由原ID完成同一take。
    pending=control('/__perf/pending',dict(id=id,user='pending-user',request='pending-original'))
    rejection=draw('pending-user',id,'pending-new')
    assert rejection['result']['code']=='D007'
    assert rejection['result']['pendingRequestId']=='pending-original'
    resumed=draw('pending-user',id,'pending-original')
    assert resumed['result']['code']=='0000' and resumed['result']['takeId']==pending['takeId']
    write(directory/'responses.json',dict(different=diff,same=same,replay=replay,timeoutObserved=timeout_observed,timeoutAttempts=attempts,timeoutReplay=retry,pending=pending,rejection=rejection,resumed=resumed))
    verify_saved(directory,id)

def verify_saved(directory,id):
    saved=json.loads((directory/'responses.json').read_text())
    diff=saved['different'];same=saved['same']
    deadline=time.time()+60
    while True:
        current=snapshot(id)
        if not current['stockPending'] and not current['grantPending']:break
        assert time.time()<deadline,'幂等场景异步未收敛'
        time.sleep(1)
    result=audit(id,directory)
    with (directory/'takes.tsv').open() as f:takes=list(csv.DictReader(f,delimiter='\t'))
    with (directory/'orders.tsv').open() as f:orders=list(csv.DictReader(f,delimiter='\t'))
    by_user=collections.Counter(t['user'] for t in takes)
    successes=sum(r['result']['code']=='0000' for r in diff)
    assert by_user=={'hot-different':successes,'hot-same':1,'timeout-user':1,'pending-user':1}
    assert len(orders)==len(takes)==sum(int(line.split('\t')[2]) for line in result['quota'].splitlines())
    assert 10000-int(result['redisActivity'])==len(takes)
    assert len({r['order'] for r in orders})==len(orders)
    assert len({r['take'] for r in orders})==len(orders)
    assert sum(int(line.split('\t')[1]) for line in result['ledger'].splitlines())==len(orders)
    assert all((r['stockState'],r['mqState'],r['grantState'])==('1','1','1') for r in orders)
    summary={'differentCodes':dict(collections.Counter(r['result']['code'] for r in diff)),'sameCodes':dict(collections.Counter(r['result']['code'] for r in same)),'newDraws':dict(by_user),'timeoutObserved':True,'pendingCode':'D007','audit':'PASS'}
    write(directory/'summary.json',summary);print(json.dumps(summary,ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--session',default='20261012-final');p.add_argument('--name',default='semantics');p.add_argument('--id',type=int,default=729001)
    p.add_argument('--audit-only',action='store_true')
    args=p.parse_args()
    if args.audit_only: verify_saved(BASE/'results'/args.session/args.name,args.id)
    else: main(args.session,args.name,args.id)
