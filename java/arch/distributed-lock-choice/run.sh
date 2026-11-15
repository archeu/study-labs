#!/usr/bin/env bash
# 分布式锁：起一个 MySQL 8.4 容器，ZooKeeper 在 JVM 里起（Curator TestingServer / TestingCluster），然后跑三段
#   scan      两个实例同时扫 outbox：不加锁 / FOR UPDATE / 租约行 / ZooKeeper / SKIP LOCKED，各发了几次
#   die       持锁的 A（单独的 JVM）被 kill -9 / kill -STOP / 发得慢，B 等多久拿到锁，9527 发了几次
#   failover  3 节点 ZooKeeper：持锁时杀 leader、再杀一台
# 用法：./run.sh [new|again] [scan|die|failover|all]（again：容器留着，只重跑）| ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
C=dlock-mysql
clean() { $DOCKER rm -fv $C >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl arch/distributed-lock-choice"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes

if [ "${1:-}" != again ]; then
  clean
  $DOCKER run -d --name $C -p 127.0.0.1:33096:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 >/dev/null
  until $DOCKER exec $C mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'select 1' >/dev/null 2>&1; do sleep 2; done
fi
java -cp "$CP" labs.arch.distributed_lock_choice.LockLab "${2:-all}"
