#!/usr/bin/env bash
# 主从复制、读写分离与复制延迟：起一主一从两个 MySQL 8.4 容器（GTID 异步复制，其余默认），灌和全景 J06 同样的 100 万行 orders，然后
#   stale  ⑤ 付完款马上读从库：隔 0 / 5 / 20 / 50 ms 读，有几次读到 CREATED；两种修法（读主库、等自己的 GTID）各花多少
#   bigtx  一个 30 万行的大事务 vs 拆成 60 批：心跳表量出的真实延迟，和 Seconds_Behind_Source 对照
#   break  在从库上手动删一行，再让主库改它：复制停在哪、报什么错；super_read_only 挡不挡得住
# 用法：./run.sh | ./run.sh again（容器留着，只重跑实验）| ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
NET=repl-net SRC=repl-src REP=repl-rep
clean() { $DOCKER rm -fv $SRC $REP >/dev/null 2>&1 || true; $DOCKER network rm $NET >/dev/null 2>&1 || true; }
[ "${1:-}" = clean ] && { clean; exit 0; }

MVN="mvn -q -f ../../pom.xml -pl database/mysql-replication-lag"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="java -cp $CP labs.database.mysql_replication_lag.ReplLab"

sql() { $DOCKER exec "$1" mysql --protocol=TCP -h127.0.0.1 -uroot -proot -N -e "$2" 2>/dev/null; }

if [ "${1:-}" != again ]; then
  clean
  $DOCKER network create $NET >/dev/null
  # GTID 要显式打开（8.4 默认 OFF）；binlog、ROW 格式、双 1、replica_parallel_workers=4 都是默认值
  common="--gtid-mode=ON --enforce-gtid-consistency=ON --innodb-buffer-pool-size=512M"
  $DOCKER run -d --name $SRC --network $NET -p 127.0.0.1:33081:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 \
    $common --server-id=1 >/dev/null
  $DOCKER run -d --name $REP --network $NET -p 127.0.0.1:33082:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 \
    $common --server-id=2 >/dev/null
  for c in $SRC $REP; do until sql $c 'select 1' >/dev/null; do sleep 2; done; done
  # 两个容器各自初始化时也写了 binlog（各自的 GTID），从库先清掉自己的，再从主库的第一个事务开始拉
  sql $SRC "CREATE USER 'repl'@'%' IDENTIFIED BY 'repl'; GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';"
  sql $REP "RESET BINARY LOGS AND GTIDS;
    CHANGE REPLICATION SOURCE TO SOURCE_HOST='$SRC', SOURCE_PORT=3306, SOURCE_USER='repl', SOURCE_PASSWORD='repl',
      SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1, SOURCE_CONNECT_RETRY=2;
    START REPLICA;"
  $J setup
fi
for step in stale bigtx break; do $J $step; done
