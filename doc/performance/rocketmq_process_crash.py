#!/usr/bin/env python3
"""复用隔离压测启动器，验证 SIGKILL 后的数据库、Redis 和 Broker 收敛。"""
import json
import os
import signal
import subprocess
import sys
import time
import urllib.error
from pathlib import Path

from environment import ROOT, sql, verify
from run import http, redis, snapshot

OUTPUT = Path(sys.argv[1]).resolve()
BEFORE, AFTER = map(int, sys.argv[2:4])


def record(event, **fields):
    print(json.dumps({'event': event, **fields}, ensure_ascii=False), flush=True)


def wait_for(fn, timeout=80):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        value = fn()
        if value:
            return value
        time.sleep(.25)
    raise TimeoutError(fn.__name__)


def start(phase, **overrides):
    with (OUTPUT / f'{phase}.log').open('w') as log:
        process = subprocess.Popen(['bash', 'doc/performance/start.sh', str(OUTPUT / phase)],
                                   cwd=ROOT, env=os.environ | overrides, stdout=log, stderr=subprocess.STDOUT)

    def ready():
        assert process.poll() is None, (phase, process.returncode)
        try:
            return http('/__perf/metrics')['pools']
        except urllib.error.URLError as error:
            if isinstance(error.reason, ConnectionRefusedError):
                return False
            raise

    try:
        wait_for(ready)
        return process
    except BaseException:
        stop(process, signal.SIGTERM)
        raise


def stop(process, sig):
    if process.poll() is None:
        process.send_signal(sig)
    code = process.wait(30)
    record('process-exit', pid=process.pid, signal=int(sig), code=code)
    if sig == signal.SIGKILL:
        assert code == -sig


def draw(activity, user, request):
    return subprocess.Popen([
        'curl', '-sS', '--max-time', '60', '--json',
        json.dumps({'userId': user, 'activityId': activity, 'requestId': request}),
        '-H', f'X-Perf-Principal: {user}', 'http://127.0.0.1:18190/api/v1/draw',
    ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)


def lost(response):
    stdout, stderr = response.communicate(timeout=15)
    assert response.returncode != 0, stdout
    record('http-lost', error=stderr.strip())


def setup(activity, user):
    # 单奖品、单件库存可检出旧预扣是否会使同请求误判售罄。
    http('/__perf/setup', dict(id=activity, stock=1, quota=3, users=1, prefix=user[:-7],
                              awardKinds=1, awardRate=1))


def stock(activity):
    return int(redis('GET', f'lottery_activity_prize_stock_count_{activity}_p{activity}_0'))


def take(activity):
    row = sql(f'SELECT take_id,state FROM lottery.draw_participation WHERE activity_id={activity};').split()
    return tuple(map(int, row)) if row else None


def completed(activity):
    counts = snapshot(activity)
    return counts['orders'] == 1 and not any(counts[k] for k in ('stockPending', 'grantPending', 'blocked'))


def replay(activity, user, request):
    response = draw(activity, user, request)
    stdout, stderr = response.communicate(timeout=20)
    assert response.returncode == 0, stderr
    payload = json.loads(stdout)
    assert payload['code'] == '0000', payload
    return payload


def audit(activity):
    assert stock(activity) == 0
    assert sql(f'SELECT award_surplus_count FROM lottery.activity_prize WHERE activity_id={activity}').strip() == '0'
    assert sql(f'SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE activity_id={activity}').strip() == '1'
    assert sql(f'SELECT left_count FROM lottery.user_activity_quota WHERE activity_id={activity}').strip() == '2'


verify()
OUTPUT.mkdir()
pre_user, post_user = 'crashpre0000000', 'crashpost0000000'
process = start('before-reserve-commit', PERF_HOLD_AFTER_RESERVE='true')
try:
    setup(BEFORE, pre_user)
    response = draw(BEFORE, pre_user, 'before-commit')
    wait_for(lambda: stock(BEFORE) == 0)
    first_take = take(BEFORE)
    assert first_take[1] == 0
    assert snapshot(BEFORE)['orders'] == 0
    # 等 Broker 通过完整截止时间，持锁 TX2 仍只能回 UNKNOWN。
    wait_for(lambda: 'state:UNKNOW' in (OUTPUT / 'before-reserve-commit.log').read_text())
    # 等到截止后再次回查，验证持锁冲突也会返回 UNKNOWN。
    time.sleep(33)
    checks = (OUTPUT / 'before-reserve-commit.log').read_text()
    assert checks.count('state:UNKNOW') >= 2
    assert 'state:ROLLBACK_MESSAGE' not in checks
    record('before-commit-hold', take=first_take, redis=stock(BEFORE), transactionCheck='UNKNOWN')
    stop(process, signal.SIGKILL)
    lost(response)
finally:
    if process.poll() is None:
        stop(process, signal.SIGTERM)

process = start('after-order-commit', PERF_HOLD_AFTER_COMMIT='true')
try:
    recovered = time.monotonic()
    wait_for(lambda: stock(BEFORE) == 1)
    assert take(BEFORE) == first_take and snapshot(BEFORE)['orders'] == 0
    wait_for(lambda: 'state:ROLLBACK_MESSAGE' in (OUTPUT / 'after-order-commit.log').read_text())
    record('before-commit-recovered', seconds=time.monotonic() - recovered, take=first_take, redis=stock(BEFORE),
           transactionCheck='ROLLBACK')
    setup(AFTER, post_user)
    response = draw(AFTER, post_user, 'after-commit')
    order_id = int(wait_for(lambda: sql(
        f'SELECT order_id FROM lottery.draw_order WHERE activity_id={AFTER}').strip()))
    assert snapshot(AFTER)['stockPending'] == 1
    stop(process, signal.SIGKILL)
    lost(response)
finally:
    if process.poll() is None:
        stop(process, signal.SIGTERM)

process = start('restart')
try:
    recovered = time.monotonic()
    wait_for(lambda: completed(AFTER))
    assert 'state:COMMIT_MESSAGE' in (OUTPUT / 'restart.log').read_text()
    resumed = replay(AFTER, post_user, 'after-commit')
    assert resumed['orderId'] == order_id
    audit(AFTER)
    record('after-commit-recovered', seconds=time.monotonic() - recovered, orderId=order_id, transactionCheck='COMMIT')
    resumed = replay(BEFORE, pre_user, 'before-commit')
    assert resumed['takeId'] == first_take[0]
    wait_for(lambda: completed(BEFORE))
    audit(BEFORE)
    record('original-request-replayed', orderId=resumed['orderId'], takeId=first_take[0])
finally:
    stop(process, signal.SIGTERM)
