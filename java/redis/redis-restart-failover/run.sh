#!/usr/bin/env bash
# 重启和切主以后：Lab 自己起停 redis:8.10.3-alpine 容器（rf-m 63791、rf-r 63792、网络 rf-net），跑完自动删。
# 用法：./run.sh              三段都跑（restart → failover → wipe），约 2 分钟
#       ./run.sh failover     只跑一段（restart / failover / wipe）
#       ./run.sh clean        删掉残留的容器和网络
# 需要 docker（本机不在 docker 组里就用 DOCKER="sudo docker" ./run.sh）。
# 商品数用 JAVA_OPTS 改，比如 JAVA_OPTS="-Dn=50000" ./run.sh restart
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}

if [ "${1:-}" = clean ]; then $DOCKER rm -fv rf-m rf-r >/dev/null 2>&1 || true; $DOCKER network rm rf-net >/dev/null 2>&1 || true; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl redis/redis-restart-failover"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
$DOCKER pull -q redis:8.10.3-alpine >/dev/null
DOCKER="$DOCKER" ${JAVA:-java} ${JAVA_OPTS:-} -cp "$CP" labs.redis.redis_restart_failover.Lab "$@"
