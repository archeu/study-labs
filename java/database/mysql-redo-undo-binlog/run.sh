#!/usr/bin/env bash
# redo、undo、binlog 与两阶段提交：起一个 MySQL 8.4 容器，灌和全景 J06 同样的 100 万行 orders，然后
#   see    ⑤ 一条付款 UPDATE：redo / undo / binlog 各动了没有；ROLLBACK 一次、COMMIT 一次
#   purge  一个开着的快照让 undo 删不掉：History list length、旧快照沿版本链读
#   redo   redo 容量 8 MB 对 1 GB，同样的批量更新
#   crash  提交途中 kill -9 mysqld 三次（见 crash.sh）
# 用法：./run.sh | ./run.sh again（容器留着，只重跑后面几段）| ./run.sh crash | ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh（crash 要用 sudo kill，总是需要 sudo）
set -euo pipefail
cd "$(dirname "$0")"
export DOCKER=${DOCKER:-docker}
export C=redo-mysql
clean() { $DOCKER rm -fv $C >/dev/null 2>&1 || true; }
case "${1:-}" in
  clean) clean; exit 0 ;;
  crash) exec ./crash.sh ;;
esac

MVN="mvn -q -f ../../pom.xml -pl database/mysql-redo-undo-binlog"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="java -cp $CP labs.database.mysql_redo_undo_binlog.LogLab"

if [ "${1:-}" != again ]; then
  clean
  # buffer pool 512 MB：整张表都在内存里；binlog、双 1 都是 8.4 默认值
  $DOCKER run -d --name $C -p 127.0.0.1:33074:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 \
    --innodb-buffer-pool-size=512M >/dev/null
  until $DOCKER exec $C mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'select 1' >/dev/null 2>&1; do sleep 2; done
  $J setup
fi
for step in see purge redo; do $J $step; done
./crash.sh
