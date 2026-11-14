#!/usr/bin/env bash
# 大促积压 100 万条：起一个单节点 Kafka 4.4.0（KRaft，broker + controller 同一进程），给 notify-svc 灌 100 万条积压，
# 再用不同的办法去消化，量出每种办法每秒能发多少条短信、会不会掀起重平衡风暴。
# 用法：./run.sh            起 Kafka、灌积压，然后 drain 和 storm 全跑一遍（约 8 分钟）
#       ./run.sh drain      只跑“怎么消化”那一组（3/6/8 个实例、车道、搬到 24 分区、共享组、跳过过期）
#       ./run.sh storm      只跑“重平衡风暴”那一组（经典协议 500 条一批 / 100 条一批 / 新协议 500 条一批）
#       ./run.sh java drain inst=6 lanes=4 ...   Kafka 留着，自己调参数跑一次（参数见 Backlog.java 顶部）
#       ./run.sh stop       停掉 Kafka；./run.sh clean 停掉并删掉 data/
# 需要 JDK 17+ 和 Maven。Kafka 包放在 KAFKA_HOME，没有就下载到 ./data/ 下。
set -euo pipefail
cd "$(dirname "$0")"
HERE=$(pwd); V=4.4.0; D=$HERE/data
KAFKA_HOME=${KAFKA_HOME:-$D/kafka_2.13-$V}
BS=localhost:19192
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
  [ -d "$KAFKA_HOME" ] || (cd "$D" && curl -sfSLO https://dlcdn.apache.org/kafka/$V/kafka_2.13-$V.tgz \
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
num.partitions=6
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
share.coordinator.state.topic.replication.factor=1
share.coordinator.state.topic.min.isr=1
P
  if [ ! -d "$D/log" ]; then
    "$KAFKA_HOME/bin/kafka-storage.sh" format -t "$("$KAFKA_HOME/bin/kafka-storage.sh" random-uuid)" \
      -c "$D/server.properties" --standalone > /dev/null
  fi
  LOG_DIR=$D/logs KAFKA_HEAP_OPTS="-Xmx1g -Xms1g" nohup "$KAFKA_HOME/bin/kafka-server-start.sh" "$D/server.properties" > "$D/kafka.out" 2>&1 &
  echo $! > "$D/kafka.pid"
  for _ in $(seq 60); do "$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server $BS --list >/dev/null 2>&1 && return; sleep 1; done
  echo "Kafka 60 秒没起来，看 $D/kafka.out"; exit 1
}
start
MVN="mvn -q -f ../../pom.xml -pl kafka/kafka-backlog-drain"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J() { java -cp "$CP" labs.kafka.kafka_backlog_drain.Backlog "$@"; }
K() { "$KAFKA_HOME/bin/$1.sh" --bootstrap-server $BS "${@:2}"; }
topics() {  # order-events 6 分区（和全景一致），notify-backlog 24 分区（临时搬家用）；积压只灌一次
  K kafka-topics --list | grep -qx order-events && return
  K kafka-topics --create --topic order-events --partitions 6 --replication-factor 1 >/dev/null
  K kafka-topics --create --topic notify-backlog --partitions 24 --replication-factor 1 >/dev/null
  J fill order-events 1000000 30
}
drain_all() {  # 每次用一个新的消费组从头读，同一份 100 万条积压；短信 20 ms 一条
  echo "== ① 平时 3 台 / 扩到 6 台 / 扩到 8 台 =="
  J drain inst=3; J drain inst=6
  J drain inst=8 group=notify-svc-8 & sleep 14
  K kafka-consumer-groups --describe --group notify-svc-8 --members | awk '{print $4, $NF}'
  K kafka-consumer-groups --describe --group notify-svc-8 | awk 'NF{print $3, $4, $5, $6}' | head -8
  wait
  echo "== ② 每个实例里按 key 分 8 条车道：不限流 / 短信平台限 1000 条/s =="
  J drain inst=6 lanes=8; J drain inst=6 lanes=8 smsLimit=1000
  echo "== ③ 搬到 24 分区的 notify-backlog，再起 24 个实例 =="
  J relay order-events notify-backlog
  J drain topic=notify-backlog inst=24
  echo "== ④ 共享组（KIP-932）：12 个、24 个消费者读 6 分区 =="
  J drain share=true inst=12; J drain share=true inst=24
  echo "== ⑤ 超过 15 分钟的“支付成功”不发短信，改站内信 =="
  J drain inst=6 skipMin=15 secs=30
}
storm_all() {  # 时间按 1:30 缩小：短信变慢到 25 ms（≈ 真实 750 ms），max.poll.interval.ms 10 s（≈ 真实 5 分钟）
  echo "== 经典协议，一批 500 条：500 × 25 ms = 12.5 s > 10 s =="
  J drain inst=3 smsMs=25 mpr=500 mpi=10000 secs=60 win=30
  echo "== 经典协议，一批 100 条：100 × 25 ms = 2.5 s =="
  J drain inst=3 smsMs=25 mpr=100 mpi=10000 secs=60 win=30
  echo "== 新协议 group.protocol=consumer，一批 500 条 =="
  J drain inst=3 smsMs=25 mpr=500 mpi=10000 secs=60 win=30 protocol=consumer
}
case "${1:-all}" in
  java) shift; J "$@" ;;
  drain) topics; drain_all ;;
  storm) topics; storm_all ;;
  all) topics; drain_all; storm_all ;;
  *) echo "用法见文件开头"; exit 1 ;;
esac
