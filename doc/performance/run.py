#!/usr/bin/env python3
"""真实 JMeter 运行、资源采样和 SQL 对账；任何采集失败均显式保存并终止成功声明。"""
import argparse
import csv
import json
import os
from pathlib import Path
import subprocess
import threading
import traceback
import time
import urllib.parse
import urllib.request
from environment import sql, verify

BASE = Path(__file__).resolve().parent
URL = 'http://127.0.0.1:18090'

def http(path, params=None):
    # HTTP 控制面仅运行在专用启动器中，正常抽奖仍由 JMeter 请求原接口。
    request = urllib.request.Request(URL+path+('?' + urllib.parse.urlencode(params) if params else ''), data=b'' if params else None)
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)

def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2))

def command(args):
    return subprocess.check_output(args, stderr=subprocess.PIPE, timeout=20).decode()

def redis(*args):
    return command(['docker','exec','lottery-perf-redis-1','redis-cli','--raw',*args])

def orders_union(id):
    return ' UNION ALL '.join(f'SELECT u_id,activity_id,order_id,take_id,award_id,stock_mq_state,mq_state,grant_state,UNIX_TIMESTAMP(update_time)*1000 AS done_ms FROM lottery_{db:02d}.user_strategy_export_{tb:03d} WHERE activity_id={id}' for db in (1,2) for tb in range(4))

def snapshot(id):
    # 各表用真实物理名审计，不通过路由器执行跨分片聚合业务查询。
    union=orders_union(id)
    query=f"SELECT COUNT(*),COALESCE(SUM(stock_mq_state<>1),0),COALESCE(SUM(mq_state<>1 OR grant_state<>1),0),COALESCE(SUM(stock_mq_state=4),0) FROM ({union}) o;"
    rows=sql(query).strip().split('\t')
    return dict(zip(('orders','stockPending','grantPending','blocked'),map(int,rows)))

def audit(id, directory):
    # 导出关联ID，随后逐项与所有 HTTP 样本核对，不能只比较总量。
    data=sql(orders_union(id)+';')
    (directory/'orders.tsv').write_text('user\tactivity\torder\ttake\taward\tstockState\tmqState\tgrantState\tdoneMs\n'+data)
    take_union=' UNION ALL '.join(f'SELECT u_id,take_id,CONVERT(request_id USING utf8mb4) AS request_id,state FROM lottery_{db:02d}.user_take_activity WHERE activity_id={id}' for db in (1,2))
    (directory/'takes.tsv').write_text('user\ttake\trequest\tstate\n'+sql(take_union+';'))
    (directory/'ledger.tsv').write_text('order\tuser\taward\n'+sql(f'SELECT order_id,u_id,award_id FROM lottery.award_stock_settlement WHERE strategy_id={id};'))
    queries=[
        ('quota', ' UNION ALL '.join(f'SELECT {db},COUNT(*),COALESCE(SUM(total_count-left_count),0),COALESCE(SUM(left_count<0),0) FROM lottery_{db:02d}.user_take_activity_count WHERE activity_id={id}' for db in (1,2))),
        ('takes', f'SELECT COUNT(*),SUM(state=0),COUNT(*)-COUNT(DISTINCT take_id) FROM ({take_union}) t'),
        ('duplicateOrders',f'SELECT COUNT(*)-COUNT(DISTINCT order_id),COUNT(*)-COUNT(DISTINCT take_id) FROM ({orders_union(id)}) o'),
        ('stock',f'SELECT award_id,award_count,award_surplus_count FROM lottery.strategy_detail WHERE strategy_id={id} ORDER BY award_id'),
        ('ledger',f'SELECT award_id,COUNT(*) FROM lottery.award_stock_settlement WHERE strategy_id={id} GROUP BY award_id'),
        ('activity',f'SELECT state,stock_count,stock_surplus_count FROM lottery.activity WHERE activity_id={id}'),
    ]
    result={name:sql(query+';').strip() for name,query in queries}
    result['pending']=snapshot(id)
    result['redisActivity']=redis('GET',f'lottery_activity_stock_count_{id}').strip()
    result['redisAwards']={f'p{id}_{i}':redis('GET',f'lottery_strategy_award_stock_count_{id}_p{id}_{i}').strip() for i in range(5)}
    result['kafka']=http('/__perf/lag')
    write(directory/'audit.json',result)
    return result

def mysql_metrics():
    return sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Connections','Slow_queries','Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_buffer_pool_reads','Questions');")

def digest(path):
    path.write_text(sql("SELECT SCHEMA_NAME,DIGEST_TEXT,COUNT_STAR,SUM_TIMER_WAIT/1000000000000,SUM_LOCK_TIME/1000000000000,SUM_ROWS_EXAMINED,SUM_ROWS_AFFECTED FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME IN ('lottery','lottery_01','lottery_02') ORDER BY SUM_TIMER_WAIT DESC;"))

def monitor(stop, directory, id):
    index=0
    try:
        with (directory/'resources.jsonl').open('w') as output:
            while not stop.is_set():
                started=time.time()
                sample={'time':int(started*1000),'app':http('/__perf/metrics')}
                if sample['app']['jobFailure']:
                    raise RuntimeError(sample['app']['jobFailure'])
                # Docker 采样包含旧容器，记录共享宿主机背景负载。
                sample['docker']=command(['docker','stats','--no-stream','--format','{{json .}}']).strip().splitlines()
                sample['processes']=command(['ps','-axo','pid,pcpu,rss,comm']).splitlines()
                # 换页增量用于识别宿主机干扰；已有 Swap 占用本身不等于正在换页。
                sample['hostVm']=command(['vm_stat'])
                # 同时保存等待方、阻塞方、实际索引与锁数据，避免凭等待数量猜根因。
                sample['lockWaits']=sql("""SELECT r.OBJECT_SCHEMA,r.OBJECT_NAME,r.INDEX_NAME,r.LOCK_TYPE,r.LOCK_MODE,r.LOCK_DATA,
                    b.INDEX_NAME,b.LOCK_MODE,b.LOCK_DATA,w.REQUESTING_ENGINE_TRANSACTION_ID,w.BLOCKING_ENGINE_TRANSACTION_ID,
                    rt.trx_query,bt.trx_query,TIMESTAMPDIFF(MICROSECOND,bt.trx_started,NOW())/1000
                    FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.data_locks b ON b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                    LEFT JOIN information_schema.innodb_trx rt ON rt.trx_id=w.REQUESTING_ENGINE_TRANSACTION_ID
                    LEFT JOIN information_schema.innodb_trx bt ON bt.trx_id=w.BLOCKING_ENGINE_TRANSACTION_ID;""")
                if index%3==0:
                    sample['mysql']=mysql_metrics()
                    sample['redis']=redis('INFO','ALL')
                    sample['redisLatency']=redis('LATENCY','LATEST')
                    sample['async']=snapshot(id)
                    sample['kafka']=http('/__perf/lag')
                sample['collectionMs']=round((time.time()-started)*1000)
                output.write(json.dumps(sample,ensure_ascii=False)+'\n');output.flush()
                index+=1
                stop.wait(max(0,3-(time.time()-started)))
    except Exception as error:
        # 保留采样失败原因，使报告无法误认指标完整。
        write(directory/'monitor-error.json',{'error':repr(error)})
        stop.set()

def run(args):
    verify()
    directory=BASE/'results'/args.session/args.name
    directory.mkdir(parents=True,exist_ok=False)
    prefix=args.name.replace('-','')[:12]+'_'
    setup=http('/__perf/setup',dict(id=args.id,stock=args.stock,quota=10,users=args.users,prefix=prefix,awardStock=args.award_stock))
    write(directory/'setup.json',setup)
    (directory/'initial-stock.tsv').write_text(sql(f'SELECT award_id,award_count,award_surplus_count FROM lottery.strategy_detail WHERE strategy_id={args.id} ORDER BY award_id;'))
    write(directory/'initial-redis.json',{'activity':redis('GET',f'lottery_activity_stock_count_{args.id}').strip(),'awards':{f'p{args.id}_{i}':redis('GET',f'lottery_strategy_award_stock_count_{args.id}_p{args.id}_{i}').strip() for i in range(5)}})
    write(directory/'parameters.json',vars(args))
    digest(directory/'sql-before.tsv')
    write(directory/'before.json',{'mysql':mysql_metrics(),'app':http('/__perf/metrics'),'lag':http('/__perf/lag')})
    stop=threading.Event()
    thread=threading.Thread(target=monitor,args=(stop,directory,args.id),daemon=True);thread.start()
    # JTL 保留业务码与参与/订单ID；有限库存 D004 单独统计为预期拒绝。
    cli=[args.jmeter,'-n','-t',str(BASE/('paced.jmx' if args.target_rps else 'draw.jmx')),'-l',str(directory/'samples.jtl'),'-j',str(directory/'jmeter.log'),f'-Jthreads={args.threads}',f'-Jduration={args.duration}','-Jramp=5',f'-Jactivity={args.id}',f'-Jprefix={prefix}',f'-Jusers={args.users}',f'-Jrun={args.name}',f'-Jfinite={str(args.stock<1000000).lower()}', '-Jsample_variables=uId,activityId,requestId,businessCode,takeId,orderId','-Jjmeter.save.saveservice.timestamp_format=ms']
    write(directory/'command.json',cli)
    cli.append(f'-JtargetRpm={args.target_rps*60}')
    write(directory/'command.json',cli)
    # 保存实际执行计划，之后修改模板不会改变这轮的复现证据。
    (directory/'plan.jmx').write_bytes(Path(cli[3]).read_bytes())
    jenv=os.environ.copy();jenv['JVM_ARGS']='-Xms256m -Xmx512m -XX:ActiveProcessorCount=2'
    try:
        with (directory/'stdout.txt').open('w') as output:
            result=subprocess.run(cli,env=jenv,stdout=output,stderr=subprocess.STDOUT,timeout=args.duration+60)
        if result.returncode:
            raise RuntimeError('JMeter 失败，查看 stdout.txt')
        # JMeter 主进程可能正常退出；真实用户耗尽必须向调用方明确返回失败。
        if '[PERF_USER_EXHAUSTED]' in (directory/'jmeter.log').read_text():
            raise RuntimeError('压测用户耗尽，负载已停止；本轮不能作为独立新抽奖性能结果')
        if stop.is_set():
            raise RuntimeError('指标采集失败，查看 monitor-error.json')
        # 保存 HTTP 停止后的真实积压；不把发送成功视为结算/发奖成功。
        ended=time.time()
        immediate={'time':int(ended*1000),'pending':snapshot(args.id),'lag':http('/__perf/lag')}
        write(directory/'http-end.json',immediate)
        deadline=ended+args.drain_timeout
        drain=[]
        while True:
            current=snapshot(args.id);drain.append({'time':int(time.time()*1000),**current})
            if not current['stockPending'] and not current['grantPending']:
                break
            if current['blocked'] or time.time()>deadline:
                write(directory/'drain.json',{'seconds':time.time()-ended,'samples':drain,'timeout':True})
                audit(args.id,directory)
                digest(directory/'sql-after.tsv')
                raise RuntimeError(f'异步链路未在{args.drain_timeout}秒内收敛')
            time.sleep(2)
        write(directory/'drain.json',{'seconds':time.time()-ended,'samples':drain})
        audit(args.id,directory)
        digest(directory/'sql-after.tsv')
        write(directory/'after.json',{'mysql':mysql_metrics(),'app':http('/__perf/metrics'),'lag':http('/__perf/lag')})
        # 审计必须实际通过后才宣称完成，不能只保存查询结果。
        from analyze import analyze
        analyze(directory)
    finally:
        stop.set();thread.join(timeout=25)
    print(directory.name+' 已执行并对账',flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--session',default='20261012');p.add_argument('--name',required=True);p.add_argument('--id',type=int,required=True)
    p.add_argument('--threads',type=int,required=True);p.add_argument('--duration',type=int,default=80);p.add_argument('--warmup',type=int,default=20)
    p.add_argument('--stock',type=int,default=1000000);p.add_argument('--award-stock',type=int,default=-1);p.add_argument('--users',type=int,default=100000)
    p.add_argument('--jmeter',default=os.environ.get('JMETER_BIN','/tmp/apache-jmeter-5.6.3/bin/jmeter'))
    p.add_argument('--target-rps',type=float,default=0)
    p.add_argument('--drain-timeout',type=int,default=180)
    args=p.parse_args()
    try:
        run(args)
    except Exception:
        # 实际失败要保留原始堆栈，并继续向调用者传播非零退出状态。
        directory=BASE/'results'/args.session/args.name
        if directory.is_dir():
            write(directory/'run-error.json',{'traceback':traceback.format_exc()})
        raise
