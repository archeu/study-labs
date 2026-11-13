#!/usr/bin/env bash
# 内存写满那一刻：起 Redis 8.10.3（端口 63798，除端口外全默认），Lab 三段各跑一遍。
# 用法：./run.sh            起容器，三段都跑（oom → policy → expire）
#       ./run.sh policy     容器留着，只跑某一段（oom / policy / expire）
#       ./run.sh clean      删掉容器
# 需要 docker（本机不在 docker 组里就用 DOCKER="sudo docker" ./run.sh）。
# 额外参数用 JAVA_OPTS 传，比如 JAVA_OPTS="-Dmax=4mb -Dn=20000" ./run.sh policy
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}

clean() { $DOCKER rm -fv oom-r8 >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl redis/redis-oom-eviction"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes

if [ -z "${1:-}" ]; then
  clean
  $DOCKER run -d --name oom-r8 -p 127.0.0.1:63798:6379 redis:8.10.3-alpine >/dev/null
  until $DOCKER exec oom-r8 redis-cli ping >/dev/null 2>&1; do sleep 1; done
fi
${JAVA:-java} ${JAVA_OPTS:-} -cp "$CP" labs.redis.redis_oom_eviction.Lab "$@"
