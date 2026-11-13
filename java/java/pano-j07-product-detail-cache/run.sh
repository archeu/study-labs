#!/usr/bin/env bash
# 全景 J07：一次商品详情查询。起一个 Redis 8.10 和一个 MySQL 8.4 容器，跑 ProductDetail 的五段。
# 用法：./run.sh          全部跑一遍（setup → trace → bench → hot → price）
#       ./run.sh hot      容器和数据留着，只跑某一段（trace / bench / hot / price）
#       ./run.sh clean    删掉两个容器（连同数据卷）
# 需要 docker（本机没在 docker 组里就用 DOCKER="sudo docker" ./run.sh）。
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}

clean() { $DOCKER rm -fv j07-redis j07-mysql >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl java/pano-j07-product-detail-cache"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="java -cp $CP labs.java.pano_j07_product_detail_cache.ProductDetail"

if [ -n "${1:-}" ]; then $J "$1"; exit 0; fi

clean
# Redis：打开 AOF（每秒 fsync 一次，这是默认的 appendfsync），其余默认
$DOCKER run -d --name j07-redis -p 127.0.0.1:63797:6379 redis:8.10.3-alpine redis-server --appendonly yes >/dev/null
$DOCKER run -d --name j07-mysql -p 127.0.0.1:33071:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=trade mysql:8.4 >/dev/null
until $DOCKER exec j07-mysql mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'select 1' >/dev/null 2>&1; do sleep 2; done
until $DOCKER exec j07-redis redis-cli ping >/dev/null 2>&1; do sleep 1; done
$J
