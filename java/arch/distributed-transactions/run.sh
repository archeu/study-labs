#!/usr/bin/env bash
# 分布式事务：起一个 MySQL 8.4 容器（里面三个库），然后跑四段
#   local    三个本地事务各提交各的，发起方死在半路
#   window   XA / TCC / Saga / 本地消息表 × 两种情况：并发买家等多久、买到没有、最后三个库什么样
#   xacrash  XA 协调者死在 PREPARE 前 / 后
#   fence    TCC：库存 Cancel 重发、券 Cancel 比 Try 先到、迟到的 Try，不开 / 开 fence
# 用法：./run.sh | ./run.sh again（容器留着，只重跑）| ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
C=dtx-mysql
clean() { $DOCKER rm -fv $C >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl arch/distributed-transactions"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes

if [ "${1:-}" != again ]; then
  clean
  $DOCKER run -d --name $C -p 127.0.0.1:33095:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 >/dev/null
  until $DOCKER exec $C mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'select 1' >/dev/null 2>&1; do sleep 2; done
fi
java -cp "$CP" labs.arch.distributed_transactions.DtxLab all
