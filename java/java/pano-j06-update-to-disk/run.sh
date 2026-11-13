#!/usr/bin/env bash
# 全景 J06：一条 UPDATE 从应用到磁盘。起一主一从两个 MySQL 8.4 容器，跑 UpdateToDisk 的三段。
# 用法：./run.sh          全部跑一遍（建库灌数 → 重启主库让 buffer pool 变冷 → 追踪一条 UPDATE → 提交开销对比）
#       ./run.sh again    容器和数据都留着，只重启主库、再追踪一次（多跑几次看波动）
#       ./run.sh clean    删掉两个容器和网络
# 需要 docker（本机没在 docker 组里就用 DOCKER="sudo docker" ./run.sh）。
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
IMG=mysql:8.4
NET=j06-net
PW=root

clean() { $DOCKER rm -f j06-primary j06-replica >/dev/null 2>&1 || true; $DOCKER network rm $NET >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

wait_up() { until $DOCKER exec "$1" mysql --protocol=TCP -h127.0.0.1 -uroot -p$PW -e 'select 1' >/dev/null 2>&1; do sleep 2; done; }
MVN="mvn -q -f ../../pom.xml -pl java/pano-j06-update-to-disk"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="java -cp $CP labs.java.pano_j06_update_to_disk.UpdateToDisk"

if [ "${1:-}" != again ]; then
clean
$DOCKER network create $NET >/dev/null
# 主库：binlog 默认就开着（ROW 格式）；打开 GTID 方便从库自动定位。
# 关掉启动时自动加载 buffer pool（8.4 默认 ON），这样重启后第一次访问能看到真实读盘。
$DOCKER run -d --name j06-primary --network $NET -p 127.0.0.1:33061:3306 -e MYSQL_ROOT_PASSWORD=$PW $IMG \
  --server-id=1 --gtid-mode=ON --enforce-gtid-consistency=ON \
  --innodb-buffer-pool-load-at-startup=OFF --innodb-buffer-pool-dump-at-shutdown=OFF >/dev/null
$DOCKER run -d --name j06-replica --network $NET -p 127.0.0.1:33062:3306 -e MYSQL_ROOT_PASSWORD=$PW $IMG \
  --server-id=2 --gtid-mode=ON --enforce-gtid-consistency=ON >/dev/null

wait_up j06-primary; wait_up j06-replica
# 走 TCP 检查：官方镜像初始化时先起的临时实例不监听端口（port: 0），这样不会被它骗过

$DOCKER exec j06-primary mysql -uroot -p$PW -e "
  CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED BY 'repl'; GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';" 2>/dev/null
$DOCKER exec j06-replica mysql -uroot -p$PW -e "
  CHANGE REPLICATION SOURCE TO SOURCE_HOST='j06-primary', SOURCE_USER='repl', SOURCE_PASSWORD='repl',
    SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1, SOURCE_CONNECT_RETRY=2; START REPLICA; SET GLOBAL super_read_only = ON;" 2>/dev/null

$J setup                                   # 建 trade.orders，灌 100 万行，等从库追上
fi
echo "== 重启主库，清空 buffer pool =="
$DOCKER restart j06-primary >/dev/null; wait_up j06-primary
# 主库重启时从库的 IO 线程断开，按 SOURCE_CONNECT_RETRY 重连（默认 60 秒，上面改成了 2 秒）
$J trace                                   # 追踪一条 UPDATE 走过的每一站
[ "${1:-}" = again ] || $J bench          # 第 9 站：提交时两次落盘值多少钱
