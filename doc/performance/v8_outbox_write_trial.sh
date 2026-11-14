#!/usr/bin/env bash
set -euo pipefail
# 复用现有 V8 栈，每轮运行同一采样器，并在负载前后记录独立的提交证据。
cd "$(dirname "$0")/../.."
export PERF_PROJECT=lottery-v8-throughput PERF_MYSQL_PORT=13359 PERF_REDIS_PORT=17499 PERF_KAFKA_PORT=29212 PERF_APP_PORT=18210
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
export PYTHONPATH=doc/performance
session=v8-outbox-write-20261114
result=doc/performance/results/$session
trial=${1:?试验名称} variant=${2:?A、B 或 C} rate=${3:?目标 RPS} activity=${4:?活动 ID}
duration=${5:-205}
users=${6:-65000}
[[ "$variant" = A || "$variant" = B || "$variant" = C ]]
export PERF_VARIANT_CLASSES="$result/classes-$variant"
test -f "$PERF_VARIANT_CLASSES/com/anontokyo/lottery/application/mq/producer/OutboxDispatcher.class"
test ! -e "$result/$trial"
# 已出现过 Broker cgroup OOM。各正式对照使用同一恢复后的资源上限。
broker=$(docker inspect "$PERF_PROJECT-kafka-1" --format '{{.State.Health.Status}} {{.HostConfig.Memory}} {{.HostConfig.MemorySwap}}')
[[ "$broker" = 'healthy 1610612736 1610612736' ]]
# 前置检查涵盖持久化、旧订单、新活动和两个消费组状态。
python3 - "$activity" "$result/$trial-db-preflight.tsv" <<'PY'
import sys
from pathlib import Path
from environment import sql

data = sql("""SELECT @@version, @@innodb_flush_log_at_trx_commit, @@sync_binlog, @@binlog_group_commit_sync_delay;
SELECT COUNT(*) FROM lottery.draw_order
WHERE stock_settlement_event_status <> 1 OR fulfillment_event_status <> 1 OR award_fulfillment_status <> 1;
SELECT COUNT(*) FROM lottery.activity WHERE activity_id=""" + str(int(sys.argv[1])) + ";")
Path(sys.argv[2]).write_text(data)
settings, pending, existing = data.splitlines()
assert settings.split()[1:] == ['1', '1', '0'], settings
assert pending == existing == '0', data
PY
if pgrep -f '(^|/)(JMeter|ApacheJMeter.jar)( |$)|java .* -cp .* PerfHarness' >/dev/null; then
  echo "已有 JMeter 或 PerfHarness 运行" >&2
  exit 1
fi

python3 doc/performance/preflight.py "$result/preflight-$trial" --seconds 20 --interval 5 > "$result/preflight-$trial.log"
bash doc/performance/start.sh "$result/app-$trial" --lottery.draw.admission.enabled=false > "$result/app-$trial.log" 2>&1 &
app=$!
trap 'kill "$app" 2>/dev/null || true; wait "$app" 2>/dev/null || true' EXIT
for _ in {1..60}; do
  if curl -fsS "http://127.0.0.1:$PERF_APP_PORT/__perf/metrics" > "$result/$trial-initial-metrics.json" 2>/dev/null; then break; fi
  kill -0 "$app"
  sleep 1
done
test -s "$result/$trial-initial-metrics.json"
curl -fsS "http://127.0.0.1:$PERF_APP_PORT/__perf/lag" > "$result/$trial-initial-lag.json"
python3 - "$result/$trial-initial-lag.json" <<'PY'
import json
import sys
from pathlib import Path
lag = json.loads(Path(sys.argv[1]).read_text())
assert len(lag) == 2 and all(row[1:3] == [0, 8] for row in lag.values()), lag
PY

echo "START $trial $variant $rate $(date '+%T')"
# 两次快照包含活动预置和收尾，用现有 SQL 快照进一步区分测量窗口。
commit_snapshot() {
  python3 - "$result/$trial-commits-$1.tsv" <<'PY'
import sys
from pathlib import Path
from environment import sql

Path(sys.argv[1]).write_text(sql("""SELECT NAME,COUNT,STATUS FROM information_schema.innodb_metrics
WHERE NAME IN ('trx_rw_commits','trx_commits_insert_update') ORDER BY NAME;
SELECT h.BUCKET_NUMBER,h.BUCKET_TIMER_HIGH,h.COUNT_BUCKET
FROM performance_schema.events_statements_histogram_by_digest h
JOIN performance_schema.events_statements_summary_by_digest s USING (SCHEMA_NAME,DIGEST)
WHERE s.SCHEMA_NAME='lottery' AND s.DIGEST_TEXT='COMMIT' ORDER BY h.BUCKET_NUMBER;"""))
PY
}
commit_snapshot before
python3 doc/performance/run.py --session "$session" --name "$trial" --id "$activity" \
  --threads 72 --target-rps "$rate" --duration "$duration" --ramp 1 --warmup 15 --users "$users" --drain-timeout 240
commit_snapshot after
python3 doc/performance/check_stability.py "$result/$trial" > "$result/$trial/stability-console.txt"
python3 doc/performance/v8_stock_diagnostics.py "$result/$trial" > "$result/$trial/v8-diagnostics.json"
python3 - "$result/$trial" <<'PY'
import json
import sys
from pathlib import Path
p = Path(sys.argv[1])
def read(name):
    return json.loads((p / (name + '.json')).read_text())
s, t, d = read('summary'), read('stability'), read('v8-diagnostics')[p.name]
print(p.name, 'stable', t['stable'], 'draw/s', round(s['newDrawRps'], 2), 'stock/s', round(s['settlementRps'], 2),
      'P99 ms', s['p99Ms'], 'drain s', round(s['drainSeconds'], 2), 'swap', d['swapMiB'], 'audit', s['audit'], flush=True)
assert s['audit'] == 'PASS'
PY
