#!/usr/bin/env bash
# 缓存的五个洞：起一个 Redis 8.10 容器（端口 63799），跑 Lab 的六段。
# 用法：./run.sh          起容器，六段全跑（pen → hot → storm → big → hotkeys → info）
#       ./run.sh hot      容器留着，只跑某一段（pen / hot / storm / big / hotkeys / info）
#       ./run.sh clean    删掉容器
# 需要 docker（本机不在 docker 组里就用 DOCKER="sudo docker" ./run.sh）。
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}

clean() { $DOCKER rm -fv cfh-redis >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl redis/cache-five-holes"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="${JAVA:-java} -cp $CP labs.redis.cache_five_holes.Lab"

if [ -n "${1:-}" ]; then $J "$1"; exit 0; fi

clean
$DOCKER run -d --name cfh-redis -p 127.0.0.1:63799:6379 redis:8.10.3-alpine >/dev/null
until $DOCKER exec cfh-redis redis-cli ping >/dev/null 2>&1; do sleep 1; done
$J
