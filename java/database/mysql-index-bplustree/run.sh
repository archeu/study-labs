#!/usr/bin/env bash
# 索引与 B+ 树：起一个 MySQL 8.4 容器，灌和全景 J06 同样的 100 万行 orders，然后
#   1) 读 orders.ibd，数每棵索引树每一层的页数和记录数（树有几层）
#   2) 几种查询各读了几个页（主键、二级索引 + 回表、覆盖索引、全表扫、回表太多）
#   3) 加联合索引 (user_id, created_at)，看最左前缀
# 用法：./run.sh | ./run.sh again（容器留着，只重跑 2、3）| ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
C=idx-mysql
clean() { $DOCKER rm -fv $C >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl database/mysql-index-bplustree"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="java -cp $CP labs.database.mysql_index_bplustree.IndexLab"

if [ "${1:-}" != again ]; then
  clean
  $DOCKER run -d --name $C -p 127.0.0.1:33071:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 >/dev/null
  # 走 TCP 检查：镜像初始化时的临时实例不监听端口，不会被它骗过
  until $DOCKER exec $C mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'select 1' >/dev/null 2>&1; do sleep 2; done
  $J setup
fi
# FLUSH TABLES ... FOR EXPORT 把这张表的脏页刷回 .ibd 并暂停写入，趁这时把文件拷出来
$DOCKER exec $C mysql -uroot -proot -e "FLUSH TABLES trade.orders FOR EXPORT; system cp /var/lib/mysql/trade/orders.ibd /tmp/orders.ibd; UNLOCK TABLES;" 2>/dev/null
$DOCKER exec $C cat /tmp/orders.ibd > target/orders.ibd   # 用 cat 不用 docker cp：拷出来的文件归当前用户
$J shape target/orders.ibd
rm -f target/orders.ibd
$J reads
$J prefix
