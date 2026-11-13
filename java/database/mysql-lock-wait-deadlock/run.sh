#!/usr/bin/env bash
# 事务、MVCC 与锁（下一圈）：起一个 MySQL 8.4 容器，灌和全景 J06 同样的 100 万行 orders，然后
#   pay       连点两次付款：三种写法各扣了几次款
#   locks     三种 WHERE 在 RR / RC 下锁了多少，另一个会话哪些操作被挡
#   wait      锁等待超时 1205 只回滚一条语句
#   deadlock  相反顺序改两行：死锁日志
# 用法：./run.sh | ./run.sh again（容器留着，只重跑后面几段）| ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
C=lock-mysql
clean() { $DOCKER rm -fv $C >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl database/mysql-lock-wait-deadlock"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="java -cp $CP labs.database.mysql_lock_wait_deadlock.LockLab"

if [ "${1:-}" != again ]; then
  clean
  # buffer pool 开到 512 MB：整张表都在内存里，测的是锁，不是磁盘
  $DOCKER run -d --name $C -p 127.0.0.1:33073:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 \
    --innodb-buffer-pool-size=512M >/dev/null
  until $DOCKER exec $C mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'select 1' >/dev/null 2>&1; do sleep 2; done
  $J setup
fi
for step in pay locks wait deadlock; do $J $step; done
