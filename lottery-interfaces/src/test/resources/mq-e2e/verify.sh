#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../../../.."
stack=lottery-interfaces/src/test/resources/mq-e2e
# 验证应用默认值，不继承本机活动用的桶数覆盖。
unset LOTTERY_STOCK_BUCKET_COUNT
# 使用固定的本机隔离端口，避免连接正在运行的开发库或 Broker。
docker compose -f "$stack/docker-compose.yml" up -d --wait
bash "$stack/create-topics.sh" lottery-rocketmq-e2e-broker-1 19911
mvn -pl lottery-interfaces -am \
  '-Dtest=FinalBusinessE2eManualIT#stockGapIsDurableBlockedAndReconciledOrderCanFinish+unrecoverableReservationsDoNotStarveLaterRefunds+unknownActivityReturnsBusinessErrorWithoutWritingDrawState' \
  -Dsurefire.failIfNoSpecifiedTests=false test
