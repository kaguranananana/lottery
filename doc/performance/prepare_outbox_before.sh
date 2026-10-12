#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# 精确使用本轮移除预读之前的 worker，不回退工作区或其它业务类。
outbox_before_revision="$(git rev-parse ':/perf: apply existing stock lookup index to isolated benchmarks')"
mkdir -p doc/performance/.variants/outbox-before/src doc/performance/.variants/outbox-before/classes
git show "$outbox_before_revision:lottery-application/src/main/java/com/anontokyo/lottery/application/worker/LotteryXxlJob.java" > doc/performance/.variants/outbox-before/src/LotteryXxlJob.java
# 只覆盖 worker 字节码，其它层继续使用当前构建，包括一致的库存字段修复。
javac -encoding UTF-8 -source 8 -target 8 -cp "$(cat doc/performance/.classpath)" -d doc/performance/.variants/outbox-before/classes doc/performance/.variants/outbox-before/src/LotteryXxlJob.java
