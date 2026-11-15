#!/usr/bin/env bash
# 分库分表与 ShardingSphere：起一个 MySQL 8.4 容器（4 个库：ds_0 ds_1 给规则 A，bad_0 bad_1 给规则 B），然后跑
#   show    每条逻辑 SQL 被改写成了哪几条真实 SQL（sql-show）
#   spread  1 万个用户，两套规则下每张真实表各有几行
# 用法：./run.sh [new|again] [show|spread|all]（again：容器留着，只重跑）| ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
C=shardlab-mysql
clean() { $DOCKER rm -fv $C >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl database/sharding-shardingsphere"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes

if [ "${1:-}" != again ]; then
  clean
  $DOCKER run -d --name $C -p 127.0.0.1:33111:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 >/dev/null
  until $DOCKER exec $C mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'select 1' >/dev/null 2>&1; do sleep 2; done
  $DOCKER exec $C mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'CREATE DATABASE ds_0; CREATE DATABASE ds_1; CREATE DATABASE bad_0; CREATE DATABASE bad_1;' 2>/dev/null
fi
# 只看 org.apache.shardingsphere.sql 这一个 logger（5.5.3 的名字；老版本叫 ShardingSphere-SQL）（sql-show 打的 Logic SQL / Actual SQL），其余只留 WARN
java -Dorg.slf4j.simpleLogger.logFile=System.out -Dorg.slf4j.simpleLogger.defaultLogLevel=warn \
     -Dorg.slf4j.simpleLogger.log.org.apache.shardingsphere.sql=info -Dorg.slf4j.simpleLogger.showThreadName=false \
     -Dorg.slf4j.simpleLogger.showLogName=false \
     -cp "$CP" labs.database.sharding_shardingsphere.ShardingLab "${2:-all}"
