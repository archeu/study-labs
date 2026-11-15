#!/usr/bin/env bash
# 全景第 9 站：order-svc 下单 500，连接池满了，谁攥着连接？不重启，attach Arthas 进去看。
#   order-svc :18111（自带一个假支付渠道 /channel/query）
#   03:00 的对账任务：10 个线程并行，每笔在 @Transactional 里调渠道；渠道被调成 20 秒才回
# 用法：./run.sh     约 1 分钟；第一次会下载 Arthas 4.3.5（约 15 MB）到 data/
set -euo pipefail
cd "$(dirname "$0")"
JAVA=${JAVA:-/usr/lib/jvm/java-17-openjdk-arm64/bin/java}   # Arthas 要 attach，得用 JDK，不能是 JRE
ARTHAS_V=4.3.5
mkdir -p data
if [ ! -f data/arthas/arthas-boot.jar ]; then
  curl -sfSL -o data/arthas.zip "https://repo1.maven.org/maven2/com/taobao/arthas/arthas-packaging/$ARTHAS_V/arthas-packaging-$ARTHAS_V-bin.zip"
  mkdir -p data/arthas && (cd data/arthas && unzip -q ../arthas.zip) && rm data/arthas.zip
fi
JAR=target/observability-arthas-diagnosis-1.0.jar
[ -f "$JAR" ] || (cd ../.. && JAVA_HOME=$(dirname "$(dirname "$JAVA")") mvn -q -pl observability/arthas-diagnosis package -DskipTests)
rm -rf data/run && mkdir -p data/run
"$JAVA" -Xmx256m -jar "$JAR" > data/run/app.log 2>&1 &
APP=$!
trap 'kill $APP 2>/dev/null; wait $APP 2>/dev/null || true' EXIT
for _ in $(seq 1 240); do curl -sf localhost:18111/actuator/health >/dev/null && break; sleep 0.5; done

as() {  # 跑一条 Arthas 命令，去掉 logo 和颜色，只留命令的输出
  "$JAVA" -jar data/arthas/arthas-boot.jar --width 200 -c "$1" $APP 2>&1 | sed 's/\x1b\[[0-9;]*m//g' |
    awk '/^\[arthas@[0-9]+\]\$ /{on=1; next} on && !/^\[arthas@|^@[0-9]+\]\$|Press Q or Ctrl|^Affect\(/'; }

for i in $(seq 1 30); do curl -s -o /dev/null -X POST "localhost:18111/api/orders?userId=$i&sku=88&qty=1"; done
echo "$(date +%T) 支付渠道变慢：每次查单 20 秒才回"; curl -s -o /dev/null -X POST 'localhost:18111/admin/channel?delayMs=20000'
echo "$(date +%T) 对账任务开始：10 个线程，60 笔（每批 10 笔、20 秒，共 2 分钟）"; curl -s -o /dev/null -X POST 'localhost:18111/admin/reconcile?count=60'
sleep 2
echo "$(date +%T) 下单："
for i in 1 2; do curl -s -o /dev/null -w "  %{http_code} %{time_total}s\n" -X POST 'localhost:18111/api/orders?userId=1024&sku=88&qty=2'; done

echo; echo "=== 1. 先看全局：哪几个线程最费 CPU（采 1 秒）==="
T0=$(date +%s.%N)
as "thread -n 3 -i 1000"
echo "  （第一次 attach 加执行用了 $(echo "$(date +%s.%N) - $T0" | bc | cut -c1-4) 秒）"

echo; echo "=== 2. 对账线程都在干什么 ==="
as "thread --all | grep recon"
RID=$(as "thread --all | grep recon-1 " | awk 'NR==1{print $1}')
echo; echo "=== 3. recon-1（id=$RID）的栈，从下往上读 ==="
as "thread $RID" | grep -E 'recon-1|SocketInputStream|NioSocketImpl|HttpURLConnection|PayChannelClient|ReconcileService|TransactionInterceptor|TransactionAspectSupport|ThreadPoolExecutor.runWorker' | head -14

echo; echo "=== 4. 池子现在什么样（直接读 HikariDataSource 对象；同时有 4 单在等连接）==="
for i in 1 2 3 4; do curl -s -o /dev/null -X POST 'localhost:18111/api/orders?userId=7&sku=88&qty=1' & done; sleep 0.5
as "vmtool --action getInstances --className com.zaxxer.hikari.HikariDataSource --express 'instances[0].getHikariPoolMXBean().getActiveConnections() + \" active, \" + instances[0].getHikariPoolMXBean().getIdleConnections() + \" idle, \" + instances[0].getHikariPoolMXBean().getThreadsAwaitingConnection() + \" waiting\"'"

echo; echo "=== 5. 线上跑的是哪版代码（反编译）==="
as "jad --source-only labs.observability.arthas_diagnosis.ReconcileService reconcileOne" | head -12

echo; echo "=== 6. reconcileOne 的时间花在哪（只看得到增强以后才开始的调用，最多等 40 秒）==="
as "trace labs.observability.arthas_diagnosis.ReconcileService reconcileOne -n 1" | head -9

echo; echo "=== 7. 看参数和返回值（同样要等下一批）==="
as "watch labs.observability.arthas_diagnosis.PayChannelClient queryOrder '{params, returnObj}' -n 1 -x 2" | head -12

as "stop" >/dev/null
echo; echo "$(date +%T) 结束，已 stop Arthas（增强的字节码都已还原）"
