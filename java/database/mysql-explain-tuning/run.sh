#!/usr/bin/env bash
# 执行计划与 SQL 调优：起一个 MySQL 8.4 容器，灌和全景 J06 同样的 100 万行 orders（多了 order_no 和 idx_created），然后
#   env    版本、慢查询变量、统计信息、代价常量
#   slow   开慢查询日志跑一段业务流量，找出最慢的 3 条
#   fix    3 条慢 SQL 改前改后的 EXPLAIN / EXPLAIN ANALYZE / 耗时
#   bench  16 种写法，索引用没用上
#   cross  回表占比 0.5%～50%：优化器在哪儿放弃索引，真实分界点在哪儿
# 用法：./run.sh | ./run.sh again（容器留着，只重跑后面几段）| ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
C=plan-mysql
clean() { $DOCKER rm -fv $C >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl database/mysql-explain-tuning"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="java -cp $CP labs.database.mysql_explain_tuning.PlanLab"

if [ "${1:-}" != again ]; then
  clean
  # buffer pool 开到 512 MB：表和 3 个二级索引都装得下，测的是“读了多少页”，不是磁盘
  $DOCKER run -d --name $C -p 127.0.0.1:33072:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 \
    --innodb-buffer-pool-size=512M >/dev/null
  until $DOCKER exec $C mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'select 1' >/dev/null 2>&1; do sleep 2; done
  $J setup
fi
for step in env slow fix bench cross; do $J $step; done
