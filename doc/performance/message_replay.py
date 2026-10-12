#!/usr/bin/env python3
"""将已审计订单真实重投 Kafka，再对账库存、账本和最终状态，不伪造消费结果。"""
import argparse
import json
import time
import urllib.parse
import urllib.request
from run import BASE, URL, audit, http, write

def run(session, semantics_name):
    source=BASE/'results'/session/semantics_name
    saved=json.loads((source/'responses.json').read_text())
    before=json.loads((source/'audit.json').read_text())
    order=saved['replay']['result']
    activity=json.loads((source/'setup.json').read_text())['activityId']
    directory=BASE/'results'/session/'message-replay';directory.mkdir(exist_ok=False)
    write(directory/'before.json',before)
    # 保留原 semantics 证据；新增目录单独保存消息重放后的审计。
    parameters={'strategy':activity,'user':'hot-same','order':order['orderId'],'award':order['award']['awardId']}
    request=urllib.request.Request(URL+'/__perf/replay?'+urllib.parse.urlencode(parameters),data=b'')
    with urllib.request.urlopen(request,timeout=15) as response:assert response.status==200
    deadline=time.monotonic()+30
    while True:
        lag=http('/__perf/lag')
        # 不只检查 Lag=0，还要求两个组各实际提交本次新增的两条消息。
        consumed=all(lag[group][0]>=before['kafka'][group][0]+2 and lag[group][1]==0 for group in before['kafka'])
        if consumed:break
        assert time.monotonic()<deadline,'Kafka 重放未完成'
        time.sleep(.2)
    after=audit(activity,directory)
    for key in ['quota','takes','duplicateOrders','stock','ledger','activity','pending','redisActivity','redisAwards']:
        assert after[key]==before[key],f'重投改变了业务事实：{key}'
    write(directory/'summary.json',{'audit':'PASS','stockMessages':2,'invoiceMessages':2,'orderId':order['orderId'],'activityId':activity,'beforeLag':before['kafka'],'afterLag':after['kafka']})
    print('真实 Kafka 重投完成，库存、订单、参与、账本和状态保持一致')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--session',required=True);parser.add_argument('--semantics',default='semantics')
    args=parser.parse_args();run(args.session,args.semantics)
