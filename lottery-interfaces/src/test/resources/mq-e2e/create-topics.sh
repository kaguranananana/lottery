#!/usr/bin/env bash
set -euo pipefail
# 在 Broker 容器内使用已注册地址，避免宿主到容器的端口差异。
broker="${1:?需要专用 Broker 容器名称}"
port="${2:?需要 Broker 端口}"
for topic in lottery_stock_transaction:TRANSACTION lottery_award_fulfillment:NORMAL; do
  docker exec -e JAVA_OPT_EXT="-Xms64m -Xmx128m -Xmn32m" "$broker" sh mqadmin updateTopic \
     -n nameserver:9876 -b "127.0.0.1:$port" -r 8 -w 8 -t "${topic%:*}" -a "+message.type=${topic#*:}"
done
