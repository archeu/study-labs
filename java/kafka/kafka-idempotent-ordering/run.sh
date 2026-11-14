#!/usr/bin/env bash
# 幂等消费与顺序消息：起一个单节点 Kafka 4.4.0（KRaft，broker + controller 同一进程），跑 IdempotentOrdering。
# 用法：./run.sh            起 Kafka，dup 四种写法 + order 五种写法全跑一遍
#       ./run.sh dup same   Kafka 留着，只跑某一种（dup: none|split|same|offset；order: seq|pool|lanes|guard|nokey）
#       ./run.sh stop       停掉 Kafka
#       ./run.sh clean      停掉，并删掉 data/
# 需要 JDK 17+ 和 Maven。Kafka 包放在 KAFKA_HOME，没有就下载到 ./data/ 下。
set -euo pipefail
cd "$(dirname "$0")"
HERE=$(pwd); V=4.4.0; D=$HERE/data
KAFKA_HOME=${KAFKA_HOME:-$D/kafka_2.13-$V}

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
  mkdir -p "$D"
  [ -d "$KAFKA_HOME" ] || (cd "$D" && curl -sfSLO https://downloads.apache.org/kafka/$V/kafka_2.13-$V.tgz \
                           && tar xzf kafka_2.13-$V.tgz && rm kafka_2.13-$V.tgz)
  if [ -f "$D/kafka.pid" ] && kill -0 "$(cat "$D/kafka.pid")" 2>/dev/null; then return; fi
  cat > "$D/server.properties" <<P
process.roles=broker,controller
node.id=1
controller.quorum.bootstrap.servers=localhost:19193
listeners=PLAINTEXT://:19192,CONTROLLER://:19193
advertised.listeners=PLAINTEXT://localhost:19192,CONTROLLER://localhost:19193
controller.listener.names=CONTROLLER
log.dirs=$D/log
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
share.coordinator.state.topic.replication.factor=1
share.coordinator.state.topic.min.isr=1
# 新成员加入时不等 3 秒，演示里实例 2 接手更快
group.initial.rebalance.delay.ms=0
P
  if [ ! -d "$D/log" ]; then
    "$KAFKA_HOME/bin/kafka-storage.sh" format -t "$("$KAFKA_HOME/bin/kafka-storage.sh" random-uuid)" \
      -c "$D/server.properties" --standalone > /dev/null
  fi
  LOG_DIR=$D/logs KAFKA_HEAP_OPTS="-Xmx512m -Xms512m" nohup "$KAFKA_HOME/bin/kafka-server-start.sh" "$D/server.properties" > "$D/kafka.out" 2>&1 &
  echo $! > "$D/kafka.pid"
  for _ in $(seq 60); do "$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server localhost:19192 --list >/dev/null 2>&1 && return; sleep 1; done
  echo "Kafka 60 秒没起来，看 $D/kafka.out"; exit 1
}

start
MVN="mvn -q -f ../../pom.xml -pl kafka/kafka-idempotent-ordering"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="java -cp $CP labs.kafka.kafka_idempotent_ordering.IdempotentOrdering"
if [ $# -ge 1 ]; then $J "$@"; exit; fi
for s in none split same offset; do $J dup $s; done
for s in seq nokey pool lanes guard; do $J order $s; done
