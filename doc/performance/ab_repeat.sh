#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
session="stock-buckets-ab-repeat-20261115"
jmeter="/tmp/lottery-rocketmq-tools/jmeter-maven/bin/jmeter"
app_pid=""

stop_app() {
  if [[ -n "${app_pid}" ]]; then
    kill "${app_pid}" 2>/dev/null || true
    wait "${app_pid}" 2>/dev/null || true
    app_pid=""
  fi
}
trap stop_app EXIT

for mode in buckets8 baseline; do
  if [[ "$mode" == "buckets8" ]]; then bucket_count=8; activity=91115101; else bucket_count=0; activity=91115102; fi
  name="repeat-${mode}-300rps"
  launcher="doc/performance/results/${session}/launcher-${mode}"
  mkdir -p "$launcher"
  echo "START_LAUNCHER mode=${mode} date=$(date '+%F %T')" | tee -a /tmp/lottery-ab-repeat-stage.log
  bash doc/performance/start.sh "$launcher" --lottery.draw.admission.enabled=false --lottery.rocketmq.consumer-threads=8 --lottery.stock.bucket-count="$bucket_count" > "$launcher/app.out" 2>&1 &
  app_pid=$!
  ready=false
  for n in $(seq 1 90); do
    if curl -fsS --max-time 4 http://127.0.0.1:18190/__perf/lag >/dev/null 2>&1; then ready=true; break; fi
    if ! kill -0 "$app_pid" 2>/dev/null; then echo 'APP_EXITED_BEFORE_READY';tail -50 "$launcher/app.out"; exit 30; fi
    sleep 1
  done
  if [[ "$ready" != true ]]; then echo "APP_NOT_READY";tail -50 "$launcher/app.out";exit 31;fi
  echo "START_TEST mode=${mode} date=$(date '+%F %T')" | tee -a /tmp/lottery-ab-repeat-stage.log
  set +e
  python3 doc/performance/run.py --session "$session" --name "$name" --id "$activity" \
    --threads 72 --duration 205 --warmup 15 --users 65000 --target-rps 300 \
    --ramp 5 --award-kinds 5 --stock-buckets "$bucket_count" --jmeter "$jmeter" > "$launcher/run.log" 2>&1
  test_exit=$?
  set -e
  if [[ "$test_exit" -eq 0 ]]; then
    python3 doc/performance/diagnostics.py "doc/performance/results/$session/$name" >> "$launcher/run.log" 2>&1
    python3 doc/performance/check_stability.py "doc/performance/results/$session/$name" >> "$launcher/run.log" 2>&1
  else
    echo "TEST_FAILED code=$test_exit mode=$mode (raw results retained)" | tee -a /tmp/lottery-ab-repeat-stage.log
    tail -25 "$launcher/run.log"
  fi
  echo "FINISH_TEST mode=${mode} exit=${test_exit} date=$(date '+%F %T')" | tee -a /tmp/lottery-ab-repeat-stage.log
  stop_app
  sleep 3
done
echo "REPEAT_FINISHED date=$(date '+%F %T')" | tee -a /tmp/lottery-ab-repeat-stage.log
