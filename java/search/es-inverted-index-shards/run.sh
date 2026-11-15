#!/usr/bin/env bash
# Elasticsearch 的倒排索引、分片、写入和查询：起一个 ES 9.5.5 单节点容器（127.0.0.1:19200，关掉安全认证，堆 1 GB），然后跑
#   setup load analyze search nrt deep mapping（见 EsLab 的注释）
# 用法：./run.sh [new|again] [setup|load|analyze|search|nrt|deep|mapping|all]（again：容器留着，只重跑）| ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
C=eslab
clean() { $DOCKER rm -fv $C >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl search/es-inverted-index-shards"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes

if [ "${1:-}" != again ]; then
  clean
  $DOCKER run -d --name $C -p 127.0.0.1:19200:9200 -e discovery.type=single-node -e xpack.security.enabled=false \
    -e "ES_JAVA_OPTS=-Xms1g -Xmx1g" elasticsearch:9.5.5 >/dev/null
  until curl -sf "http://127.0.0.1:19200/_cluster/health?wait_for_status=yellow&timeout=5s" >/dev/null; do sleep 2; done
fi
java -cp "$CP" labs.search.es_inverted_index_shards.EsLab ${2:-all}
