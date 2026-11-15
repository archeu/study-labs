#!/usr/bin/env bash
# 不改一行代码，用 OpenTelemetry Java agent 给两个服务接上链路，span 发给 Jaeger，再把 trace 取回来画成瀑布图。
#   Jaeger 2.23.0（内存存储，OTLP 4318，查询 16686）
#   order-svc :18101 → product-svc :18102，都挂 opentelemetry-javaagent 2.33.1
# 用法：./run.sh     约 1 分半；第一次会下载 agent（26 MB）和 Jaeger（约 50 MB 压缩包）到 data/
set -euo pipefail
cd "$(dirname "$0")"
JAVA=${JAVA:-/usr/lib/jvm/java-17-openjdk-arm64/bin/java}
AGENT_V=2.33.1; JAEGER_V=2.23.0; ARCH=${ARCH:-linux-arm64}
AGENT=data/opentelemetry-javaagent-$AGENT_V.jar; JAEGER=data/jaeger-$JAEGER_V-$ARCH/jaeger
mkdir -p data
[ -f "$AGENT" ] || curl -sfSL -o "$AGENT" "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/$AGENT_V/opentelemetry-javaagent-$AGENT_V.jar"
[ -x "$JAEGER" ] || (cd data && curl -sfSL "https://github.com/jaegertracing/jaeger/releases/download/v$JAEGER_V/jaeger-$JAEGER_V-$ARCH.tar.gz" | tar xz)
JAR=target/observability-tracing-otel-skywalking-1.0.jar
[ -f "$JAR" ] || (cd ../.. && JAVA_HOME=$(dirname "$(dirname "$JAVA")") mvn -q -pl observability/tracing-otel-skywalking package -DskipTests)
rm -rf data/run && mkdir -p data/run
PIDS=()
trap 'kill "${PIDS[@]}" 2>/dev/null; wait 2>/dev/null || true' EXIT

wait_up() { for _ in $(seq 1 240); do curl -sf "$1" >/dev/null && return 0; sleep 0.5; done; echo "等不到 $1，看 data/run/ 里的日志"; exit 1; }
"$JAEGER" > data/run/jaeger.log 2>&1 & PIDS+=($!)
wait_up localhost:16686/

otel() {  # 用 agent 起一个服务：otel <服务名> <端口> [其他参数]
  local name=$1 port=$2; shift 2
  OTEL_SERVICE_NAME=$name OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 \
  OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=none \
  "$JAVA" -Xmx256m -javaagent:"$AGENT" -jar "$JAR" --spring.application.name=$name --server.port=$port "$@" \
    > data/run/$name.log 2>&1 & PIDS+=($!)
}
otel product-svc 18102
otel order-svc 18101
for p in 18101 18102; do wait_up localhost:$p/actuator/health; done

order() { curl -s -o /dev/null -w '%{http_code} %{time_total}s\n' -X POST 'localhost:18101/api/orders?userId=1024&sku=88&qty=2'; }
echo; echo "== 正常下 5 单 =="; for i in 1 2 3 4 5; do order; done
echo; echo "== 埋雷：order-svc 上 10 个线程攥住全部连接 15 秒，期间下 2 单 =="
curl -s -X POST 'localhost:18101/admin/hold?threads=10&seconds=15' >/dev/null; sleep 0.5
order; order
sleep 15
echo "  日志（trace_id 是 agent 放进 MDC 的）："
grep 'ERROR' data/run/order-svc.log | tail -1 | grep -o 'ERROR \[trace_id=[0-9a-f]*\]'
grep -m1 'Connection is not available' data/run/order-svc.log | cut -c1-160
echo "  通知线程（普通 JDK 线程池）的日志："; grep 'notify user' data/run/order-svc.log | tail -1 | grep -o 'INFO \[trace_id=[0-9a-f]*\].*\[pool-[^]]*\]'


sleep 8   # agent 默认每 5 秒批量导出一次
echo; echo "== 从 Jaeger 取回：最近一条正常的下单 =="
python3 show_trace.py http://localhost:16686 order-svc max=1s 1 | tee data/run/normal.txt
echo "== 从 Jaeger 取回：耗时超过 2 秒的那条 =="
python3 show_trace.py http://localhost:16686 order-svc min=2s 1
