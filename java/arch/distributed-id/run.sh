#!/usr/bin/env bash
# 分布式 ID：起一个 MySQL 8.4 容器，然后跑三段
#   segment    号段：两个实例各领一段、9527 怎么来的、重启丢号、40 万个号查重、库慢 200 ms 时单 buffer / 双 buffer、库挂了还能撑多久
#   snowflake  雪花：拆开一个 ID、低流量下按 id % 4 分库、时钟回拨（MyBatis-Plus 的真实 Sequence 和照 Seata 写的变种）、两个实例同一个 workerId
#   btree      往 InnoDB 插 30 万行：雪花 bigint / 随机 UUID / 按时间排序的 UUID v7，比耗时、页分裂、占用空间
# 用法：./run.sh [new|again] [segment|snowflake|btree|all]（again：容器留着，只重跑）| ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
C=idlab-mysql
clean() { $DOCKER rm -fv $C >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl arch/distributed-id"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes

if [ "${1:-}" != again ]; then
  clean
  $DOCKER run -d --name $C -p 127.0.0.1:33097:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 >/dev/null
  until $DOCKER exec $C mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'select 1' >/dev/null 2>&1; do sleep 2; done
fi
java -cp "$CP" labs.arch.distributed_id.IdLab "${2:-all}"
