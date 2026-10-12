#!/usr/bin/env python3
"""两连接屏障复现锁范围，只在新建的本轮 fixture 库操作，不改业务数据。"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import threading
import time
from environment import sql, ROOT

def experiment(schema, table, held_sql, other_sql):
    # 第一连接执行语句后等待三秒，便于观察第二连接是否必须等其提交。
    cli=['docker','exec','-i','lottery-perf-mysql-1','mysql','-uroot','-pperf-only','-N','-B']
    held=subprocess.Popen(cli,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    held.stdin.write(f'USE {schema}; START TRANSACTION; {held_sql}; SELECT SLEEP(3); ROLLBACK;'.encode())
    held.stdin.close()
    deadline=time.monotonic()+2
    while True:
        # 等实际行锁出现后才放行第二连接，避免用启动时间猜测事务已经执行。
        active=sql(f"SELECT COUNT(*) FROM performance_schema.data_locks WHERE OBJECT_SCHEMA='{schema}' AND OBJECT_NAME='{table}' AND LOCK_TYPE='RECORD';")
        if int(active.strip()):break
        assert time.monotonic()<deadline,'第一连接没有建立预期行锁'
        time.sleep(.02)
    result={}
    errors=[]
    def invoke():
        start=time.monotonic()
        try:
            sql(f'USE {schema}; {other_sql};')
            result['otherSeconds']=time.monotonic()-start
            result['completedBeforeHeldRollback']=held.poll() is None
        except Exception as error:
            errors.append(error)
    worker=threading.Thread(target=invoke);worker.start()
    deadline=time.monotonic()+1
    waits=''
    while worker.is_alive() and time.monotonic()<deadline:
        waits=sql(f"""SELECT r.INDEX_NAME,r.LOCK_MODE,r.LOCK_DATA,b.INDEX_NAME,b.LOCK_MODE,b.LOCK_DATA
            FROM performance_schema.data_lock_waits w
            JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
            JOIN performance_schema.data_locks b ON b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
            WHERE r.OBJECT_SCHEMA='{schema}';""")
        if waits:break
        time.sleep(.02)
    worker.join(timeout=6)
    assert not worker.is_alive(),'第二连接未完成'
    assert held.wait(timeout=6)==0,held.stderr.read().decode()
    if errors:raise errors[0]
    result['waits']=waits
    return result

def run(output):
    # 每次新建名字，CREATE 冲突显式失败，不清空既有 fixture。
    schema='lottery_perf_probe_'+str(int(time.time()*1000))
    sql(f"CREATE DATABASE {schema}; CREATE TABLE {schema}.user_take_activity_count LIKE lottery_01.user_take_activity_count; CREATE TABLE {schema}.strategy_detail LIKE lottery.strategy_detail;")
    sql(f"INSERT INTO {schema}.user_take_activity_count(u_id,activity_id,total_count,left_count) VALUES ('userA',1,10,10),('userB',1,10,10);")
    # 固定 75 行，与初步诊断时生产压测表的大小相同；两种奖品分属不同记录。
    stock_rows=','.join(f"({1 if i<5 else i},'award{i}',10000,10000)" for i in range(75))
    sql(f'INSERT INTO {schema}.strategy_detail(strategy_id,award_id,award_count,award_surplus_count) VALUES {stock_rows};')
    held="INSERT INTO user_take_activity_count(u_id,activity_id,total_count,left_count) VALUES ('userA',1,10,10) ON DUPLICATE KEY UPDATE u_id=u_id"
    other="INSERT INTO user_take_activity_count(u_id,activity_id,total_count,left_count) VALUES ('userB',1,10,10) ON DUPLICATE KEY UPDATE u_id=u_id"
    result={'schema':schema,'heldSeconds':3,'upsert':experiment(schema,'user_take_activity_count',held,other)}
    result['readThenUpdate']=experiment(schema,'user_take_activity_count',held,"SELECT total_count,left_count FROM user_take_activity_count WHERE u_id='userB' AND activity_id=1; UPDATE user_take_activity_count SET left_count=left_count-1 WHERE u_id='userB' AND activity_id=1 AND left_count>0")
    first="UPDATE strategy_detail SET award_surplus_count=award_surplus_count-1 WHERE strategy_id=1 AND award_id='award0' AND award_surplus_count>0"
    second=first.replace('award0','award1')
    # 先记录原始计划，再执行已有脚本中相同的库存索引定义，不重新设计重复索引。
    result['stockPlanBefore']=sql(f'USE {schema}; EXPLAIN {second};')
    result['stockWithoutIndex']=experiment(schema,'strategy_detail',first,second)
    source=(ROOT/'doc/sql/20260314_high_concurrency_index.sql').read_text()
    index=re.search(r'ALTER TABLE `strategy_detail`.*?;',source,re.S).group()
    sql(f'USE {schema}; {index}')
    result['stockPlanAfter']=sql(f'USE {schema}; EXPLAIN {second};')
    result['stockWithIndex']=experiment(schema,'strategy_detail',first,second)
    # 这些断言验证具体锁机制，不把人为三秒持锁时间当作真实业务性能提升。
    assert 'supremum pseudo-record' in result['upsert']['waits']
    assert not result['upsert']['completedBeforeHeldRollback']
    assert result['readThenUpdate']['completedBeforeHeldRollback'] and not result['readThenUpdate']['waits']
    assert result['stockWithoutIndex']['waits'] and not result['stockWithoutIndex']['completedBeforeHeldRollback']
    assert result['stockWithIndex']['completedBeforeHeldRollback'] and not result['stockWithIndex']['waits']
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('output',type=Path)
    run(parser.parse_args().output)
