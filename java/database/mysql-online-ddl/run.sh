#!/usr/bin/env bash
# 表结构设计与大表变更（Online DDL）：起一主一从两个 MySQL 8.4 容器（GTID 异步复制，其余默认），灌和全景 J06 同样的 100 万行 orders，然后
#   algos  ⑤ 一边每 20 ms 付一笔款，一边做 INSTANT 加列 / INPLACE 加列 / INPLACE 加索引 / COPY 改类型：主库用时、付款最慢多久、从库晚多久；INSTANT 行版本上限
#   mdl    一个没提交的事务 + 一条 INSTANT ALTER，把 40 个用户请求全堵住；修法 lock_wait_timeout=1 + 重试
# 用法：./run.sh | ./run.sh again（容器留着，重灌数据再跑）| ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
NET=ddl-net SRC=ddl-src REP=ddl-rep
clean() { $DOCKER rm -fv $SRC $REP >/dev/null 2>&1 || true; $DOCKER network rm $NET >/dev/null 2>&1 || true; }
[ "${1:-}" = clean ] && { clean; exit 0; }

MVN="mvn -q -f ../../pom.xml -pl database/mysql-online-ddl"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="java -cp $CP labs.database.mysql_online_ddl.DdlLab"

sql() { $DOCKER exec "$1" mysql --protocol=TCP -h127.0.0.1 -uroot -proot -N -e "$2" 2>/dev/null; }

if [ "${1:-}" != again ]; then
  clean
  $DOCKER network create $NET >/dev/null
  # GTID 要显式打开（8.4 默认 OFF）；binlog、ROW 格式、replica_parallel_workers=4 都是默认值
  common="--gtid-mode=ON --enforce-gtid-consistency=ON --innodb-buffer-pool-size=512M"
  $DOCKER run -d --name $SRC --network $NET -p 127.0.0.1:33091:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 \
    $common --server-id=1 >/dev/null
  $DOCKER run -d --name $REP --network $NET -p 127.0.0.1:33092:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 \
    $common --server-id=2 >/dev/null
  for c in $SRC $REP; do until sql $c 'select 1' >/dev/null; do sleep 2; done; done
  sql $SRC "CREATE USER 'repl'@'%' IDENTIFIED BY 'repl'; GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';"
  sql $REP "RESET BINARY LOGS AND GTIDS;
    CHANGE REPLICATION SOURCE TO SOURCE_HOST='$SRC', SOURCE_PORT=3306, SOURCE_USER='repl', SOURCE_PASSWORD='repl',
      SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1, SOURCE_CONNECT_RETRY=2;
    START REPLICA;"
fi
# 每段实验前都重建 orders，互不影响
for step in algos mdl; do $J setup; $J $step; done
