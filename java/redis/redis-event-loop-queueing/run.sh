#!/usr/bin/env bash
# 279 µs 里只有 2 µs 在 Redis：起一个 Redis 8.10 容器（端口 63799），跑 Lab 的三段。
# 用法：./run.sh          起容器（Linux 上直连容器 IP），三段全跑（share → slow → block）
#       ./run.sh slow     容器留着，只跑某一段（share / slow / block）
#       ./run.sh clean    删掉容器
# 需要 docker（本机不在 docker 组里就用 DOCKER="sudo docker" ./run.sh）。
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}

clean() { $DOCKER rm -fv reloop-redis >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl redis/redis-event-loop-queueing"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="${JAVA:-java} -cp $CP labs.redis.redis_event_loop_queueing.Lab"

if [ -n "${1:-}" ]; then $J "$@"; exit 0; fi

clean
$DOCKER run -d --name reloop-redis -p 127.0.0.1:63799:6379 redis:8.10.3-alpine >/dev/null
until $DOCKER exec reloop-redis redis-cli ping >/dev/null 2>&1; do sleep 1; done
# Linux 上直连容器 IP，绕开 docker-proxy（它在用户态转发每个字节，单次来回多 30 多 µs）；macOS 上容器 IP 不通，走端口映射
if [ "$(uname)" = Linux ]; then
  IP=$($DOCKER inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' reloop-redis)
  J="${JAVA:-java} -Dhost=$IP -Dport=6379 -cp $CP labs.redis.redis_event_loop_queueing.Lab"
fi
$J
