#!/usr/bin/env bash
# 缓存一致性：起一个 Redis 8.10 和一个 MySQL 8.4 容器，跑 PriceChange 的三组实验。
# 用法：./run.sh            全部跑一遍（natural → forced → binlog）
#       ./run.sh forced     容器留着，只跑某一组（natural / forced / binlog）
#       ./run.sh clean      删掉两个容器（连同数据卷）
# 需要 docker（本机没在 docker 组里就用 DOCKER="sudo docker" ./run.sh）。
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}

clean() { $DOCKER rm -fv cc-redis cc-mysql >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl redis/cache-consistency"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="java -cp $CP labs.redis.cache_consistency.PriceChange"

if [ -n "${1:-}" ]; then $J "$1"; exit 0; fi

clean
$DOCKER run -d --name cc-redis -p 127.0.0.1:63798:6379 redis:8.10.3-alpine >/dev/null
# MySQL 8.4 默认就开着 binlog（ROW 格式），订阅者伪装成 server-id 9001 的从库
$DOCKER run -d --name cc-mysql -p 127.0.0.1:33072:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=trade mysql:8.4 --server-id=1 >/dev/null
until $DOCKER exec cc-mysql mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'select 1' >/dev/null 2>&1; do sleep 2; done
until $DOCKER exec cc-redis redis-cli ping >/dev/null 2>&1; do sleep 1; done
$J
