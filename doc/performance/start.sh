#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# 宿主机启动应用，固定可用处理器与堆；输出路径由调用者提供。
output="${1:?需要本轮结果目录}"
mkdir -p "$output"
# 端口已被占用时显式失败，防止第二个测试进程抢占消费者分区。
python3 - <<'PY'
import socket
with socket.socket() as s:
    assert s.connect_ex(('127.0.0.1',18090)) != 0, '18090 已有应用运行，请先停止专用启动器'
PY
echo "$$" > "$output/application.pid"
# 实验覆盖目录只改变指定测试类，便于在同一修复版本上对照一个 Outbox 变量。
classpath="doc/performance/.classes:$(cat doc/performance/.classpath)"
variant_classes="${2:-}"
if [[ -n "$variant_classes" ]]; then
  classpath="$variant_classes:$classpath"
fi
# exec 保留真正应用 PID，方便资源采样和精确停止。
exec java -Xms512m -Xmx1024m -XX:ActiveProcessorCount=4 -Xlog:gc*:file="$output/gc.log":time,uptime,level,tags -cp "$classpath" PerfHarness --spring.config.additional-location="file:$PWD/doc/performance/application-perf.yml"
