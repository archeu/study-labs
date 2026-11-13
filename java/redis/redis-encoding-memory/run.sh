#!/usr/bin/env bash
# 648 字节的详情占 805 字节：起 Redis 8.10.3（63798）和 7.2（63772）两个容器，Lab 三段各跑一遍。
# 用法：./run.sh            起容器，两个版本都跑（one → embstr → shapes）
#       ./run.sh one        容器留着，只跑某一段（one / embstr / shapes），两个版本都跑
#       ./run.sh clean      删掉容器
# 需要 docker（本机不在 docker 组里就用 DOCKER="sudo docker" ./run.sh）。
# 额外参数用 JAVA_OPTS 传，比如 JAVA_OPTS="-Dn=100000 -DsameDetail=true" ./run.sh shapes
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}

clean() { $DOCKER rm -fv enc-r8 enc-r72 >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl redis/redis-encoding-memory"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes

if [ -z "${1:-}" ]; then
  clean
  $DOCKER run -d --name enc-r8 -p 127.0.0.1:63798:6379 redis:8.10.3-alpine >/dev/null
  $DOCKER run -d --name enc-r72 -p 127.0.0.1:63772:6379 redis:7.2-alpine >/dev/null
  for c in enc-r8 enc-r72; do until $DOCKER exec $c redis-cli ping >/dev/null 2>&1; do sleep 1; done; done
fi
for port in 63798 63772; do
  ${JAVA:-java} ${JAVA_OPTS:-} -Dport=$port -cp "$CP" labs.redis.redis_encoding_memory.Lab "$@"
done
