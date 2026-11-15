#!/usr/bin/env bash
# 从 MySQL 换到 TiDB 的四个差异：TiDB 8.5.8 小集群（复用 ../distributed-db-tidb-oceanbase/cluster）+ MySQL 8.4 容器（127.0.0.1:33115）
# 用法：./run.sh [up] [autoinc|hotspot|bigtxn|iso|all]（up：先起 TiDB 集群和 MySQL）| ./run.sh down
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh up all
set -euo pipefail
cd "$(dirname "$0")"
export DOCKER=${DOCKER:-docker}
CLUSTER=../distributed-db-tidb-oceanbase/cluster/cluster.sh
if [ "${1:-}" = down ]; then $CLUSTER down; $DOCKER rm -fv pitfall-mysql >/dev/null 2>&1 || true; exit 0; fi
if [ "${1:-}" = up ]; then
  $CLUSTER up
  $DOCKER rm -fv pitfall-mysql >/dev/null 2>&1 || true
  $DOCKER run -d --name pitfall-mysql -p 127.0.0.1:33115:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 >/dev/null
  until $DOCKER exec pitfall-mysql mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e "select 1" >/dev/null 2>&1; do sleep 2; done
  shift
fi
MVN="mvn -q -f ../../pom.xml -pl database/mysql-to-tidb-pitfalls"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
java -cp "$CP" labs.database.mysql_to_tidb_pitfalls.PitfallsLab ${1:-all}
