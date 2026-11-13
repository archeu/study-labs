#!/usr/bin/env bash
# 锁过期时 A 还在查库：起一个 Redis 8.10 容器（端口 63798），跑 Lab 的三段。
# 用法：./run.sh          起容器，三段全跑（stale → dog → lua）
#       ./run.sh stale    容器留着，只跑某一段（stale / dog / lua）
#       ./run.sh clean    删掉容器
# 需要 docker（本机不在 docker 组里就用 DOCKER="sudo docker" ./run.sh）。dog 段用 kill -STOP，要 Linux 或 macOS。
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}

clean() { $DOCKER rm -fv rlf-redis >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl redis/redis-lock-expire-fencing"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="${JAVA:-java} -cp $CP labs.redis.redis_lock_expire_fencing.Lab"

if [ -n "${1:-}" ]; then $J "$@"; exit 0; fi

clean
$DOCKER run -d --name rlf-redis -p 127.0.0.1:63798:6379 redis:8.10.3-alpine >/dev/null
until $DOCKER exec rlf-redis redis-cli ping >/dev/null 2>&1; do sleep 1; done
$J
