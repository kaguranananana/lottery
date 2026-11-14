#!/usr/bin/env bash
set -euo pipefail
# 只在独立 V8 压测栈执行，两个新活动各产生一笔抽奖，保留进程退出与恢复证据。
cd "$(dirname "$0")/../.."
export PERF_PROJECT=lottery-v8-throughput PERF_MYSQL_PORT=13359 PERF_REDIS_PORT=17499 PERF_KAFKA_PORT=29212 PERF_APP_PORT=18210
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
export PYTHONPATH=doc/performance
result=doc/performance/results/v8-outbox-write-20261114
# 只为故障演练编译计时器覆盖类，正式 A/B 始终使用原计时器。
mkdir "$result/crash-harness"
python3 - "$result/crash-harness/PerfTiming.java" <<'INJECT'
import sys
from pathlib import Path
source = Path('doc/performance/java/PerfTiming.java').read_text()
marker = '                    super.doCommit(status);'
assert source.count(marker) == 1
gate = """                    // TX2 已执行两条真实 DML，发送 COMMIT 前暂停。
                    if ("TX2".equals(phase) && Boolean.getBoolean("lottery.perf.hold-before-tx2-commit")) {
                        System.out.println("PERF_HOLD_BEFORE_TX2_COMMIT");
                        new java.util.concurrent.Semaphore(0).acquireUninterruptibly();
                    }
"""
Path(sys.argv[1]).write_text(source.replace(marker, gate + marker))
INJECT
javac -encoding UTF-8 -cp "doc/performance/.classes:$(cat doc/performance/.classpath)" \
  -d "$result/crash-harness" "$result/crash-harness/PerfTiming.java"
export PERF_VARIANT_CLASSES="$result/crash-harness:$result/classes-B"

python3 - "$result" "${1:?提交前退出活动 ID}" "${2:?提交后退出活动 ID}" <<'PY'
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import urllib.error

from run import APP_PORT, audit, http, redis, snapshot, sql

base = Path(sys.argv[1]) / 'crash-B'
base.mkdir()
before, after = map(int, sys.argv[2:])
log = (base / 'events.jsonl').open('w')


def record(event, **data):
    print(json.dumps({'event': event, **data}, ensure_ascii=False), file=log, flush=True)


def wait_for(fn, timeout=45):
    end = time.monotonic() + timeout
    while True:
        result = fn()
        if result:
            return result
        if time.monotonic() >= end:
            raise TimeoutError(fn.__name__)
        time.sleep(.25)


def start(name, hold_after=False, hold_before=False):
    env = os.environ | {'PERF_HOLD_AFTER_COMMIT': str(hold_after).lower()}
    if hold_before:
        env['JAVA_TOOL_OPTIONS'] = '-Dlottery.perf.hold-before-tx2-commit=true'
    output = (base / f'{name}.log').open('w')
    process = subprocess.Popen(['bash', 'doc/performance/start.sh', str(base / name),
                                '--lottery.draw.admission.enabled=false'],
                               env=env, stdout=output, stderr=subprocess.STDOUT)
    output.close()

    def ready():
        assert process.poll() is None, (name, process.returncode)
        try:
            return http('/__perf/metrics')
        except urllib.error.URLError as error:
            if isinstance(error.reason, ConnectionRefusedError):
                return False
            raise

    try:
        wait_for(ready)
    except BaseException:
        stop(process, signal.SIGTERM)
        raise
    return process


def stop(process, sig):
    if process.poll() is None:
        process.send_signal(sig)
    code = process.wait(30)
    record('process-exit', pid=process.pid, signal=sig, code=code)
    if sig == signal.SIGKILL:
        assert code == -signal.SIGKILL, code


def draw(activity, user, request_id):
    # 走正常 HTTP 与现有合成身份入口，响应丢失由进程退出本身产生。
    command = [
        'curl', '-sS', '--max-time', '50', '--json',
        json.dumps({'userId': user, 'activityId': activity, 'requestId': request_id}),
        '-H', f'X-Perf-Principal: {user}', f'http://127.0.0.1:{APP_PORT}/api/v1/draw',
    ]
    return subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)


def setup(activity, user):
    assert not sql(f'SELECT activity_id FROM lottery.activity WHERE activity_id={activity};').strip()
    http('/__perf/setup', dict(id=activity, stock=10, quota=10, users=1, prefix=user[:-7]))


def assert_drained(activity, expect_orders):
    state = snapshot(activity)
    lag = http('/__perf/lag')
    if (state['orders'] == expect_orders and not any(state[k] for k in ('stockPending', 'grantPending', 'blocked'))
            and all(lagrow[1:3] == [0, 8] for lagrow in lag.values())):
        return state


pending = sql("""SELECT COUNT(*) FROM lottery.draw_order WHERE stock_settlement_event_status<>1
                  OR fulfillment_event_status<>1 OR award_fulfillment_status<>1;""").strip()
assert pending == '0', pending
# 在 TX2 执行写语句后、提交前暂停。TX1 与 Redis 预扣已结束，外部连接看不到订单。
process = start('before-commit', hold_before=True)
try:
    user = 'crashpre0000000'
    setup(before, user)
    response = draw(before, user, 'tx2-before')
    # 文件标记由真实事务管理器发出，此时 INSERT 已执行但外部连接看不到订单。
    wait_for(lambda: 'PERF_HOLD_BEFORE_TX2_COMMIT' in (base / 'before-commit.log').read_text())
    record('before-commit-hold', pending=snapshot(before))
    assert snapshot(before)['orders'] == 0
    stop(process, signal.SIGKILL)
    output, error = response.communicate(timeout=15)
    assert response.returncode != 0, output
    record('before-commit-http', error=error)
    def rolled_back():
        return sql(f"SELECT state FROM lottery.draw_participation WHERE activity_id={before};").strip() == '0'
    wait_for(rolled_back)
    assert snapshot(before)['orders'] == 0
    assert sql(f'SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE activity_id={before};').strip() == '0'
    stocks = {i: int(redis('GET', f'lottery_activity_prize_stock_count_{before}_p{before}_{i}')) for i in range(5)}
    assert sum(stocks.values()) == 49
    record('before-commit-observed', pending=snapshot(before), redisStocks=stocks,
           ledger=0, unfinishedParticipation=1)
finally:
    if process.poll() is None:
        stop(process, signal.SIGTERM)

# TX2 已提交但尚未安排任何发送，进程退出后由定时调度从原订单恢复。
process = start('after-commit', hold_after=True)
try:
    user = 'crashpost0000000'
    setup(after, user)
    response = draw(after, user, 'tx2-after')
    def committed():
        return sql(f'SELECT order_id FROM lottery.draw_order WHERE activity_id={after} '
                   'AND stock_attempt=1 AND stock_settlement_event_status=0;').strip()
    order_id = wait_for(committed)
    assert sql(f'SELECT COUNT(*) FROM lottery.award_stock_settlement WHERE order_id={int(order_id)};').strip() == '0'
    record('after-commit-order', orderId=order_id, pending=snapshot(after))
    stop(process, signal.SIGKILL)
    output, error = response.communicate(timeout=15)
    assert response.returncode != 0, output
    record('after-commit-http', error=error)
finally:
    if process.poll() is None:
        stop(process, signal.SIGTERM)

process = start('restart')
try:
    begin = time.monotonic()
    state = wait_for(lambda: assert_drained(after, 1))
    recovery = time.monotonic() - begin
    response = draw(after, user, 'tx2-after')
    output, error = response.communicate(timeout=15)
    assert response.returncode == 0, error
    assert str(json.loads(output)['orderId']) == order_id, output
    result = audit(after, base)
    assert result['quota'].split()[1:] == ['1', '0']
    assert sum(int(r.split()[1]) for r in result['ledger'].splitlines()) == 1
    for award, initial, remaining in (row.split() for row in result['stock'].splitlines()):
        assert result['redisAwards'][award] == remaining
    record('after-commit-recovered', secondsAfterReady=recovery, orderId=order_id, pending=state)

    # 提交前场景已记录原有缺口。只对独立测试活动和已核实的单次预扣做人工对账。
    reserved, = (index for index, amount in stocks.items() if amount == 9)
    key = f'lottery_activity_prize_stock_count_{before}_p{before}_{reserved}'
    assert redis('GET', key).strip() == '9'
    assert redis('INCR', key).strip() == '10'
    response = draw(before, 'crashpre0000000', 'tx2-before')
    output, error = response.communicate(timeout=15)
    assert response.returncode == 0 and json.loads(output)['code'] == '0000', (output, error)
    state = wait_for(lambda: assert_drained(before, 1))
    before_audit = base / 'before-repair-audit'
    before_audit.mkdir()
    result = audit(before, before_audit)
    assert result['quota'].split()[1:] == ['1', '0']
    assert len((before_audit / 'ledger.tsv').read_text().splitlines()) == 2
    for award, initial, remaining in (row.split() for row in result['stock'].splitlines()):
        assert result['redisAwards'][award] == remaining
    record('before-commit-manually-reconciled', pending=state)
finally:
    stop(process, signal.SIGTERM)
PY
