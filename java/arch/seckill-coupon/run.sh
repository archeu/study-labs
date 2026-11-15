#!/usr/bin/env bash
# 抢券实验：起一个 Redis 8.10 容器（端口 63799），跑 CouponRushLab 的四段。
# 用法：./run.sh          起容器并运行
#       ./run.sh clean    删掉容器
# 需要 docker（本机不在 docker 组里就用 DOCKER="sudo docker" ./run.sh）。
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
clean() { $DOCKER rm -fv seckill-redis >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi
MVN="mvn -q -f ../../pom.xml -pl arch/seckill-coupon"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
clean
$DOCKER run -d --name seckill-redis -p 127.0.0.1:63799:6379 redis:8.10.3-alpine >/dev/null
until $DOCKER exec seckill-redis redis-cli ping >/dev/null 2>&1; do sleep 1; done
${JAVA:-java} -cp "$CP" labs.arch.seckill_coupon.CouponRushLab
