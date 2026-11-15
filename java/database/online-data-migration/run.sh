#!/usr/bin/env bash
# 不停机迁移演练：起一个 MySQL 8.4 容器（旧库 order_db，新库 ds_0 ds_1），然后跑
#   naive safe（见 MigrationLab 的注释），每种从头建表、灌 20 万行、跑一遍完整演练
# 用法：./run.sh [new|again] [naive|safe|all]（again：容器留着，只重跑）| ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
C=mig-mysql
clean() { $DOCKER rm -fv $C >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl database/online-data-migration"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes

if [ "${1:-}" != again ]; then
  clean
  $DOCKER run -d --name $C -p 127.0.0.1:33113:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 >/dev/null
  until $DOCKER exec $C mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'select 1' >/dev/null 2>&1; do sleep 2; done
  $DOCKER exec $C mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'CREATE DATABASE order_db; CREATE DATABASE ds_0; CREATE DATABASE ds_1;' 2>/dev/null
fi
# 只看 org.apache.shardingsphere.sql 这一个 logger（5.5.3 的名字；老版本叫 ShardingSphere-SQL）（sql-show 打的 Logic SQL / Actual SQL），其余只留 WARN
java -Dorg.slf4j.simpleLogger.logFile=System.out -Dorg.slf4j.simpleLogger.defaultLogLevel=warn \
     -Dorg.slf4j.simpleLogger.log.org.apache.shardingsphere.sql=info -Dorg.slf4j.simpleLogger.showThreadName=false \
     -Dorg.slf4j.simpleLogger.showLogName=false \
     -cp "$CP" labs.database.online_data_migration.MigrationLab ${2:-all}
