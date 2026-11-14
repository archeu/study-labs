#!/usr/bin/env bash
# 幂等设计：起一个 MySQL 8.4 容器，然后跑四段
#   entry  同一个幂等键的两次 POST：先查后插 / 唯一键同事务 / 唯一键先提交，各出几张单
#   crash  第一下插完键就死了，同一个键再来会怎样
#   state  券和库存的 Confirm 被重试 3 次：状态机写法 vs “再减 2”
#   dedup  去重表和业务在不在同一个本地事务
# 用法：./run.sh | ./run.sh again（容器留着，只重跑）| ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
C=idem-mysql
clean() { $DOCKER rm -fv $C >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl arch/idempotency-design"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes

if [ "${1:-}" != again ]; then
  clean
  $DOCKER run -d --name $C -p 127.0.0.1:33094:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 >/dev/null
  until $DOCKER exec $C mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'select 1' >/dev/null 2>&1; do sleep 2; done
fi
java -cp "$CP" labs.arch.idempotency_design.IdemLab all
