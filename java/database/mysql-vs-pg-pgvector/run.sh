#!/usr/bin/env bash
# 工单库要加“找相似故障”：起一个带 pgvector 的 PostgreSQL 18 容器，然后
#   setup   建 tickets 表，16 张故障单连同向量（ollama bge-m3，连不上就用哈希向量）一个事务写入
#   see     ⑤ 一条 SQL 里同时按服务、时间过滤和按向量排序；新单子写入回滚 / 提交后搜得到搜不到
#   filter  10 万行 128 维 + HNSW：带 WHERE 时结果少于 LIMIT；iterative_scan；加 B-tree 后计划器改走精确路线
# 用法：./run.sh | ./run.sh again（容器留着，只重跑实验）| ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
# ollama 不在 127.0.0.1:11434：加 -Dollama=http://host:port 或先 ollama pull bge-m3
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
C=pgv-lab
clean() { $DOCKER rm -fv $C >/dev/null 2>&1 || true; }
[ "${1:-}" = clean ] && { clean; exit 0; }

MVN="mvn -q -f ../../pom.xml -pl database/mysql-vs-pg-pgvector"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="java -cp $CP labs.database.mysql_vs_pg_pgvector.VecLab"

if [ "${1:-}" != again ]; then
  clean
  # --shm-size：并行建 HNSW 时图放在共享内存里，Docker 默认 /dev/shm 只有 64 MB，会报 could not resize shared memory segment
  $DOCKER run -d --name $C --shm-size=1g -p 127.0.0.1:55419:5432 -e POSTGRES_PASSWORD=pg pgvector/pgvector:pg18 \
    -c shared_buffers=512MB >/dev/null
  until $DOCKER exec $C psql -h 127.0.0.1 -U postgres -c 'select 1' >/dev/null 2>&1; do sleep 2; done
fi
for step in setup see filter; do $J $step; done
