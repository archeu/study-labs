#!/usr/bin/env bash
# 起 order-svc，下一单，然后从三个角度看同一个请求：日志、指标、链路。
# 用法：./run.sh            正常下单
#       ./run.sh async      雷 1：通知交给普通线程池
#       ./run.sh cardinality 雷 2：订单号放进指标标签，连下 200 单
set -euo pipefail
cd "$(dirname "$0")"
MINE=${1:-none}
JAVA=${JAVA:-/usr/lib/jvm/java-17-openjdk-arm64/bin/java}
JAR=target/observability-logs-metrics-traces-1.0.jar
[ -f "$JAR" ] || (cd ../.. && JAVA_HOME=$(dirname "$(dirname "$JAVA")") mvn -q -pl observability/logs-metrics-traces package -DskipTests)
mkdir -p target/run
"$JAVA" -jar "$JAR" > target/run/app.log 2>&1 &
APP=$!
trap 'kill $APP 2>/dev/null; wait $APP 2>/dev/null || true' EXIT
until curl -sf localhost:18080/actuator/health >/dev/null; do sleep 0.5; done

N=5; [ "$MINE" = cardinality ] && N=200   # 前几单 JIT 还没热，看最后一单
for i in $(seq 1 "$N"); do
  RESP=$(curl -s -X POST "localhost:18080/api/orders?userId=1024&sku=88&qty=2&mine=$MINE")
done
TRACE=$(echo "$RESP" | sed -E 's/.*"traceId":"([0-9a-f]+)".*/\1/')
sleep 0.5
echo "== 响应 ==";  echo "$RESP"
echo; echo "== 日志：按 trace_id 搜 =="; grep "$TRACE" target/run/app.log || true
echo; echo "== 日志：通知那一行 =="; grep 'notify user' target/run/app.log | tail -1 || true
echo; echo "== 指标：这个请求相关的几条 =="
curl -s localhost:18080/actuator/prometheus | grep -E '^(http_server_requests_seconds_count\{.*uri="/api/orders"|order_insert_seconds_count|orders_placed_total|hikaricp_connections_(active|max)\{)' || true
echo "orders_placed_by_id_total 序列条数: $(curl -s localhost:18080/actuator/prometheus | grep -c "^orders_placed_by_id_total" || true)"
echo "全部时间序列条数: $(curl -s localhost:18080/actuator/prometheus | grep -vc '^#')"
echo; echo "== 指标里的 exemplar（OpenMetrics 格式才有）=="
curl -s -H 'Accept: application/openmetrics-text; version=1.0.0' localhost:18080/actuator/prometheus | grep 'uri="/api/orders"' | grep 'trace_id' | head -2 || true
echo; echo "== 链路：同一个 trace_id 的 span 树 =="; curl -s "localhost:18080/debug/trace/$TRACE"
