#!/usr/bin/env bash
# 凌晨三点那次故障，在本机重演一遍，量出“从出错到电话响”到底要多久。
#   3 个 order-svc 实例（18091～18093）+ Prometheus 3.16（19090）+ Alertmanager 0.35（19093）+ 假手机（19095）
#   每秒 12 单平均打到三个实例；第 45 秒起，order-3 上 10 个“对账线程”各攥一条连接 150 秒。
# 用法：./run.sh            整个过程约 6 分钟，输出在 data/run/
# 第一次运行会下载 Prometheus 和 Alertmanager（约 140 MB）到 data/。
set -euo pipefail
cd "$(dirname "$0")"
JAVA=${JAVA:-/usr/lib/jvm/java-17-openjdk-arm64/bin/java}
PROM_V=3.16.0; AM_V=0.35.0; ARCH=${ARCH:-linux-arm64}
PROM=data/prometheus-$PROM_V.$ARCH; AM=data/alertmanager-$AM_V.$ARCH
mkdir -p data && cd data
[ -d "prometheus-$PROM_V.$ARCH" ] || curl -sfSL "https://github.com/prometheus/prometheus/releases/download/v$PROM_V/prometheus-$PROM_V.$ARCH.tar.gz" | tar xz
[ -d "alertmanager-$AM_V.$ARCH" ] || curl -sfSL "https://github.com/prometheus/alertmanager/releases/download/v$AM_V/alertmanager-$AM_V.$ARCH.tar.gz" | tar xz
cd ..
JAR=target/observability-prometheus-alerting-1.0.jar
[ -f "$JAR" ] || (cd ../.. && JAVA_HOME=$(dirname "$(dirname "$JAVA")") mvn -q -pl observability/prometheus-alerting package -DskipTests)
rm -rf data/run data/prom data/am && mkdir -p data/run
PIDS=()
trap 'kill "${PIDS[@]}" 2>/dev/null; wait 2>/dev/null || true' EXIT
for i in 1 2 3; do
  "$JAVA" -Xmx256m -jar "$JAR" --server.port=1809$i > data/run/order-$i.log 2>&1 & PIDS+=($!)
done
"$JAVA" -cp target/classes labs.observability.prometheus_alerting.WebhookReceiver 19095 > data/run/phone.log 2>&1 & PIDS+=($!)
"$AM/alertmanager" --config.file=conf/alertmanager.yml --storage.path=data/am \
  --web.listen-address=127.0.0.1:19093 --cluster.listen-address= > data/run/alertmanager.log 2>&1 & PIDS+=($!)
"$PROM/prometheus" --config.file=conf/prometheus.yml --storage.tsdb.path=data/prom \
  --web.listen-address=127.0.0.1:19090 > data/run/prometheus.log 2>&1 & PIDS+=($!)
for p in 18091 18092 18093; do until curl -sf localhost:$p/actuator/health >/dev/null; do sleep 0.5; done; done
until curl -sf localhost:19090/-/ready >/dev/null; do sleep 0.5; done
echo "$(date +%T) 全部就绪"

"$JAVA" -cp target/classes labs.observability.prometheus_alerting.LoadGen 12 330 18091,18092,18093 > data/run/load.log 2>&1 & PIDS+=($!)
"$JAVA" -cp target/classes labs.observability.prometheus_alerting.AlertWatcher 19090 340 > data/run/rules.log 2>&1 & PIDS+=($!)
sleep 45
echo "$(date +%T) T0 注入：order-3 上 10 个线程各攥一条连接 150 秒" | tee data/run/t0.log
curl -s -X POST 'localhost:18093/admin/hold?threads=10&seconds=150' >/dev/null

q() { curl -s localhost:19090/api/v1/query --data-urlencode "query=$1" | python3 -c '
import json,sys
for r in json.load(sys.stdin)["data"]["result"]:
    m=r["metric"]; k=" ".join(f"{a}={b}" for a,b in sorted(m.items()) if a in ("pod","le","__name__"))
    print("   ", k or "(合计)", "=", round(float(r["value"][1]), 4))'; }
sleep 90
echo "$(date +%T) T0+90s，在 Prometheus 里问几个问题："
echo "  每秒下单（按实例）："; q 'sum by (pod) (rate(http_server_requests_seconds_count{uri="/api/orders"}[1m]))'
echo "  错误率（录制规则，按实例）："; q 'pod:order_create_error_ratio:rate1m'
echo "  三个实例合起来的 P99（秒）："; q 'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/orders"}[1m])))'
echo "  等连接的线程数："; q 'hikaricp_connections_pending'
echo "  拿连接超时次数（近 1 分钟增量）："; q 'sum by (pod) (increase(hikaricp_connections_timeout_total[1m]))'
sleep 200
echo
echo "== 事件，按时间排序（T0 = 注入时刻）=="
sort -s -k1,1 data/run/t0.log data/run/rules.log data/run/phone.log data/run/load.log | grep -v 'receiver on'
