#!/usr/bin/env bash
# 积分服务停了 8 天：起一个单节点 Kafka 4.4.0（KRaft），打开分层存储（远端用 Kafka 自带的测试插件 LocalTieredStorage，
# 把“对象存储”模拟成本机的一个目录），然后演三场：
#   outage   积分服务停 8 天再回来：普通 topic（保留 7 天） vs 分层存储 topic（本地 1 天、总共 30 天），位点也过期
#   compact  订单状态 topic 开日志压缩：每个订单只留最新状态；空 key、compact + 分层存储会被拒绝
#   down     远端存储不可用：读老数据的消费者、读新数据的消费者、生产者各怎样
# 时间按比例缩小：1 天 = 60/7 秒 ≈ 8.6 秒，这样“位点保留 7 天”正好是 offsets.retention.minutes 能设的最小值 1 分钟。
# 用法：./run.sh [outage|compact|down]   不带参数三场都跑（约 4 分钟）
#       ./run.sh stop / ./run.sh clean    停 Kafka / 停掉并删 data/
# 需要 JDK 17+ 和 Maven。Kafka 包放在 KAFKA_HOME，没有就下载到 ./data/ 下。
set -euo pipefail
cd "$(dirname "$0")"
HERE=$(pwd); V=4.4.0; D=$HERE/data
KAFKA_HOME=${KAFKA_HOME:-$D/kafka_2.13-$V}
BS=localhost:19292
FIX=$D/plugin/kafka-storage-$V-test-fixtures.jar
stop() {
  [ -f "$D/kafka.pid" ] || return 0
  pid=$(cat "$D/kafka.pid"); kill "$pid" 2>/dev/null || true
  for _ in $(seq 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  kill -9 "$pid" 2>/dev/null || true; rm -f "$D/kafka.pid"
}
case "${1:-}" in
  stop) stop; exit 0 ;;
  clean) stop; rm -rf "$D"; exit 0 ;;
esac
start() {
  mkdir -p "$D/plugin" "$D/remote"
  [ -d "$KAFKA_HOME" ] || (cd "$D" && curl -sfSLO https://dlcdn.apache.org/kafka/$V/kafka_2.13-$V.tgz \
                           && tar xzf kafka_2.13-$V.tgz && rm kafka_2.13-$V.tgz)
  # 4.4.0 里 LocalTieredStorage 在 kafka-storage 的 test-fixtures 包里（4.3 及以前在 test 包里）
  [ -f "$FIX" ] || curl -sfSL -o "$FIX" https://repo1.maven.org/maven2/org/apache/kafka/kafka-storage/$V/kafka-storage-$V-test-fixtures.jar
  if [ -f "$D/kafka.pid" ] && kill -0 "$(cat "$D/kafka.pid")" 2>/dev/null; then return; fi
  cat > "$D/server.properties" <<P
process.roles=broker,controller
node.id=1
controller.quorum.bootstrap.servers=localhost:19293
listeners=PLAINTEXT://:19292,CONTROLLER://:19293
advertised.listeners=PLAINTEXT://localhost:19292,CONTROLLER://localhost:19293
controller.listener.names=CONTROLLER
log.dirs=$D/log
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
share.coordinator.state.topic.replication.factor=1
share.coordinator.state.topic.min.isr=1
# —— 时间缩小：检查得勤一点 ——
log.retention.check.interval.ms=2000
offsets.retention.minutes=1
offsets.retention.check.interval.ms=2000
log.cleaner.backoff.ms=1000
# —— 分层存储 ——
remote.log.storage.system.enable=true
remote.log.storage.manager.class.name=org.apache.kafka.server.log.remote.storage.LocalTieredStorage
remote.log.storage.manager.class.path=$FIX
remote.log.storage.manager.impl.prefix=rsm.config.
rsm.config.dir=$D/remote
remote.log.metadata.manager.listener.name=PLAINTEXT
remote.log.metadata.manager.impl.prefix=rlmm.config.
rlmm.config.remote.log.metadata.topic.replication.factor=1
remote.log.manager.task.interval.ms=2000
P
  if [ ! -d "$D/log" ]; then
    "$KAFKA_HOME/bin/kafka-storage.sh" format -t "$("$KAFKA_HOME/bin/kafka-storage.sh" random-uuid)" \
      -c "$D/server.properties" --standalone > /dev/null
  fi
  LOG_DIR=$D/logs KAFKA_HEAP_OPTS="-Xmx768m -Xms768m" nohup "$KAFKA_HOME/bin/kafka-server-start.sh" "$D/server.properties" > "$D/kafka.out" 2>&1 &
  echo $! > "$D/kafka.pid"
  for _ in $(seq 60); do "$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server $BS --list >/dev/null 2>&1 && return; sleep 1; done
  echo "Kafka 60 秒没起来，看 $D/kafka.out"; exit 1
}
start
MVN="mvn -q -f ../../pom.xml -pl kafka/kafka-tiered-compact"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J() { java -cp "$CP" labs.kafka.kafka_tiered_compact.Retention "$@" bs=$BS logDir="$D/log" remoteDir="$D/remote"; }
case "${1:-all}" in
  outage)  J outage ;;
  compact) J compact
           echo "== kafka-dump-log：order-state 分区 0 压缩后的段（offset 有空洞） =="
           for f in "$D"/log/order-state-0/*.log; do
             "$KAFKA_HOME/bin/kafka-dump-log.sh" --files "$f" --print-data-log 2>/dev/null \
               | grep -E 'key: 9527 ' | sed -E 's/.*(offset: [0-9]+).*(key: [^ ]+).*(payload: [^ ]*).*/\1 \2 \3/' || true
           done ;;
  down)    J down ;;
  all)     J outage; J compact; J down ;;
  *) echo "用法：./run.sh [outage|compact|down|stop|clean]"; exit 1 ;;
esac
