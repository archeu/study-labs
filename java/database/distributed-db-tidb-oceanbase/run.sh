#!/usr/bin/env bash
# 分布式数据库：在本机 TiDB 8.5.8 小集群上跑 TidbLab（tour：Region、副本、下推、TSO；failover：杀一台 TiKV）
# 用法：./run.sh [up] [tour|failover|all]（up：先起集群）| ./run.sh down
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh up all
set -euo pipefail
cd "$(dirname "$0")"
export DOCKER=${DOCKER:-docker}
if [ "${1:-}" = down ]; then cluster/cluster.sh down; exit 0; fi
if [ "${1:-}" = up ]; then cluster/cluster.sh up; shift; fi
MVN="mvn -q -f ../../pom.xml -pl database/distributed-db-tidb-oceanbase"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
java -cp "$CP" labs.database.distributed_db_tidb_oceanbase.TidbLab ${1:-all}
