#!/usr/bin/env bash
# PostgreSQL 的多版本、VACUUM、索引类型和 JSONB：起一个 PostgreSQL 18 容器，灌和全景 J06 同样的 100 万行 orders（多一列 ext jsonb），然后
#   see     ⑤ 一条付款 UPDATE：旧版本留在表里（ctid / xmin / xmax / 页内槽位），VACUUM 之后才清掉
#   bloat   整表改一遍：表胖一倍；VACUUM 不缩表但能复用；VACUUM FULL 才缩回去
#   longtx  一个开着的 REPEATABLE READ 事务让 VACUUM 删不掉：9527 被改 2 万次后谁读得慢
#   hot     fillfactor 100 对 90：同样改 1 万行 status，有几次是 HOT 更新，索引长了多少
#   index   JSONB 的 GIN（jsonb_ops / jsonb_path_ops）、表达式索引、BRIN、部分索引：大小和查询耗时
# 用法：./run.sh | ./run.sh again（容器留着，只重跑实验）| ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
C=pg-mvcc
clean() { $DOCKER rm -fv $C >/dev/null 2>&1 || true; }
[ "${1:-}" = clean ] && { clean; exit 0; }

MVN="mvn -q -f ../../pom.xml -pl database/pg-mvcc-vacuum-jsonb"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="java -cp $CP labs.database.pg_mvcc_vacuum_jsonb.PgLab"

if [ "${1:-}" != again ]; then
  clean
  # shared_buffers 512 MB：整张表都在内存里，和 MySQL 那几页的 buffer pool 对齐；其余默认
  $DOCKER run -d --name $C -p 127.0.0.1:55418:5432 -e POSTGRES_PASSWORD=pg postgres:18.7-alpine \
    -c shared_buffers=512MB >/dev/null
  until $DOCKER exec $C psql -h 127.0.0.1 -U postgres -c 'select 1' >/dev/null 2>&1; do sleep 2; done
  $J setup
fi
for step in see bloat longtx hot index; do $J $step; done
