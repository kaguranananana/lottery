#!/usr/bin/env bash
set -euo pipefail
# 在仓库根目录编译原应用；不改生产依赖或打包方式。
cd "$(dirname "$0")/../.."
mvn -q install -DskipTests
mvn -q -pl lottery-interfaces dependency:build-classpath -Dmdep.outputFile=target/perf-classpath.txt -Dmdep.includeScope=runtime
python3 - <<'PY'
from pathlib import Path
root=Path.cwd()
modules=['lottery-interfaces','lottery-application','lottery-domain','lottery-infrastructure','lottery-common']
# 优先实际 reactor 编译类，防止本地 Maven 旧制品混入测量。
classes=[str(root/m/'target/classes') for m in modules]
cp=(root/'lottery-interfaces/target/perf-classpath.txt').read_text().strip()
(root/'doc/performance/.classpath').write_text(':'.join(classes)+':'+cp)
PY
javac -encoding UTF-8 -source 8 -target 8 -cp "$(cat doc/performance/.classpath)" -d doc/performance/.classes doc/performance/java/PerfHarness.java
