#!/usr/bin/env bash
# 连接池与事务性能（下一圈）：起一个 MySQL 8.4 容器（默认配置，只把 buffer pool 设成 512 MB），建 trade 库，然后跑 PayLab：
#   hot   热点行：风控 20 ms 放在事务里 / 挪出事务、88 号库存先改 / 最后改、池子 10 / 50
#   size  没有热点行：池子 1～64 的吞吐、延迟、每次提交摊到几次 fsync
# 用法：./run.sh [秒数，默认 8] | ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
DB=pool-db
clean() { $DOCKER rm -fv $DB >/dev/null 2>&1 || true; }
[ "${1:-}" = clean ] && { clean; exit 0; }
SECS=${1:-8}

MVN="mvn -q -f ../../pom.xml -pl database/db-pool-tx-perf-hot-row"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="java -cp $CP labs.database.db_pool_tx_perf_hot_row.PayLab"

if ! $DOCKER ps --format '{{.Names}}' | grep -qx $DB; then
  clean
  # 双 1 默认就开着：innodb_flush_log_at_trx_commit=1、sync_binlog=1；max_connections 默认 151
  $DOCKER run -d --name $DB -p 127.0.0.1:33093:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 \
    --innodb-buffer-pool-size=512M >/dev/null
  until $DOCKER exec $DB mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'select 1' >/dev/null 2>&1; do sleep 2; done
fi
$J setup
$J hot "$SECS"
$J setup
$J size "$SECS"
