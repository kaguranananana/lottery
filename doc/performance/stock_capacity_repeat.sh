#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
session="stock-buckets-capacity-20261115"
app_pid=""
stop_app() {
 if [[ -n "${app_pid}" ]]; then
   kill "${app_pid}" 2>/dev/null || true
   wait "${app_pid}" 2>/dev/null || true
   app_pid=""
 fi
}
trap stop_app EXIT
for repeat in 1 2; do
 id=$((91115120 + repeat))
 name="buckets8-rate290-run${repeat}"
 app="doc/performance/results/$session/launcher-${repeat}"
 mkdir -p "$app"
 echo "LAUNCH ${repeat} $(date '+%F %T')" | tee -a /tmp/lottery-bucket-capacity-stage.log
 bash doc/performance/start.sh "$app" --lottery.draw.admission.enabled=false \
     --lottery.rocketmq.consumer-threads=8 --lottery.stock.bucket-count=8 > "$app/app.out" 2>&1 &
 app_pid=$!
 ready=0
 for x in $(seq 1 60); do
   if curl -fsS --max-time 5 http://127.0.0.1:18190/__perf/lag >/dev/null 2>&1; then ready=1;break;fi
   if ! kill -0 "$app_pid" 2>/dev/null;then tail -40 "$app/app.out";exit 30;fi
   sleep 1
 done
 if [[ "$ready" -ne 1 ]];then echo NOT_READY;exit 31;fi
 echo "TEST ${repeat} $(date '+%F %T')" | tee -a /tmp/lottery-bucket-capacity-stage.log
 set +e
 python3 doc/performance/run.py --session "$session" --name "$name" --id "$id" \
   --threads 72 --duration 205 --warmup 15 --users 65000 --target-rps 290 \
   --ramp 5 --award-kinds 5 --stock-buckets 8 \
   --jmeter /tmp/lottery-rocketmq-tools/jmeter-maven/bin/jmeter > "$app/run.log" 2>&1
 status=$?
 set -e
 if [[ "$status" -eq 0 ]];then
   python3 doc/performance/diagnostics.py "doc/performance/results/$session/$name" >> "$app/run.log" 2>&1
   python3 doc/performance/check_stability.py "doc/performance/results/$session/$name" >> "$app/run.log" 2>&1
 else
   echo "RUN_FAILED run=$repeat exit=$status";tail -20 "$app/run.log"
 fi
 echo "FINISHED ${repeat} exit=$status $(date '+%F %T')" | tee -a /tmp/lottery-bucket-capacity-stage.log
 stop_app
 sleep 3
done
echo "COMPLETE $(date '+%F %T')" | tee -a /tmp/lottery-bucket-capacity-stage.log
