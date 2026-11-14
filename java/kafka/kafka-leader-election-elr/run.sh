#!/usr/bin/env bash
# 机架断电演练：Kafka 4.4.0（KRaft：1 个 controller + 3 个 broker），order-events 6 分区、3 副本、min.insync.replicas=2。
# 只盯订单 9527 所在的分区 3：先停一个 follower（它从此落后），再停另一个，最后停 leader，然后看谁回来、谁能当 leader。
# 用法：./run.sh            = ./run.sh laggard
#       ./run.sh laggard    只有落后的那台回来：等不到合格副本，用 unclean 选举强行让它当 leader，看丢了什么
#       ./run.sh elr-kill   第二台 follower 是 kill -9 停的，它先回来：在不在 ELR 里，能不能当 leader
#       ./run.sh elr-clean  第二台 follower 是正常关机（SIGTERM）停的，它先回来：用 ELR 干净接班
#       ./run.sh leader-first  最后一任 leader（数据最全）先回来，第二台 follower 还没回来：它能不能当 leader
#       ./run.sh stop       停掉 4 个 Kafka 进程；./run.sh clean 再删掉 data/ 下的数据和日志
# 需要 JDK 17+ 和 Maven。Kafka 包放在 KAFKA_HOME，没有就下载到 ./data/ 下。每个剧本都从新集群开始，约 2～3 分钟。
set -euo pipefail
cd "$(dirname "$0")"
HERE=$(pwd); V=4.4.0; D=$HERE/data
KAFKA_HOME=${KAFKA_HOME:-$D/kafka_2.13-$V}
PORTS=(19092 29092 39092)
BS=localhost:19092,localhost:29092,localhost:39092
T0=$(date +%s)
say() { printf '[%3ds] %s\n' $(( $(date +%s) - T0 )) "$*"; }

stop_all() {  # 先停 broker，再停 controller
  for n in b1 b2 b3 c0; do
    f=$D/$n.pid; [ -f "$f" ] || continue
    pid=$(cat "$f"); kill "$pid" 2>/dev/null || true
    for _ in $(seq 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$pid" 2>/dev/null || true; rm -f "$f"
  done
}
case "${1:-}" in
  stop) stop_all; exit 0 ;;
  clean) stop_all; rm -rf "$D"/c0 "$D"/b? "$D"/logs "$D"/*.properties; exit 0 ;;
esac
SCENARIO=${1:-laggard}

MVN="mvn -q -f ../../pom.xml -pl kafka/kafka-leader-election-elr"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
drill() { java -cp "$CP" labs.kafka.kafka_leader_election_elr.Drill "$@"; }
p3() {  # 分区 3 的一行描述：Leader / Replicas / Isr / Elr / LastKnownElr
  "$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server "$1" --describe --topic order-events 2>/dev/null \
    | grep -E 'Partition: 3\b' | tr -s '\t ' ' ' | sed -E 's/^ ?Topic: order-events TopicId: [^ ]+ //'
}
alive_bs() { local s=""; for i in 1 2 3; do [ -f "$D/b$i.pid" ] && s="$s,localhost:${PORTS[$((i-1))]}"; done; echo "${s#,}"; }
show() { say "分区 3：$(p3 "$(alive_bs)")"; }
wait_isr() {  # 等分区 3 的 Isr 变成 $1（逗号分隔，按 describe 的顺序无所谓）
  local want=$(echo "$1" | tr ',' '\n' | sort | paste -sd,)
  for _ in $(seq 60); do
    got=$(p3 "$(alive_bs)" | sed -E 's/.*Isr: ([0-9,]*).*/\1/' | tr ',' '\n' | sort | paste -sd,)
    [ "$got" = "$want" ] && return 0; sleep 1
  done; say "（等了 60 s，Isr 还是 [$got]）"
}
start_broker() {
  LOG_DIR=$D/logs/b$1 KAFKA_HEAP_OPTS="-Xmx512m -Xms512m" nohup "$KAFKA_HOME/bin/kafka-server-start.sh" "$D/b$1.properties" >/dev/null 2>&1 &
  echo $! > "$D/b$1.pid"
}
kill9() { kill -9 "$(cat "$D/b$1.pid")"; rm -f "$D/b$1.pid"; }
term() { local pid; pid=$(cat "$D/b$1.pid"); kill "$pid"; while kill -0 "$pid" 2>/dev/null; do sleep 1; done; rm -f "$D/b$1.pid"; }

# ---------- 起集群 ----------
mkdir -p "$D/logs"
[ -x "$KAFKA_HOME/bin/kafka-server-start.sh" ] || (cd "$D" && curl -sfSLO https://downloads.apache.org/kafka/$V/kafka_2.13-$V.tgz && tar xzf kafka_2.13-$V.tgz && rm kafka_2.13-$V.tgz)
stop_all; rm -rf "$D"/c0 "$D"/b1 "$D"/b2 "$D"/b3 "$D"/logs/*
CID=$("$KAFKA_HOME/bin/kafka-storage.sh" random-uuid)
cat > "$D/c0.properties" <<EOF
process.roles=controller
node.id=100
controller.quorum.bootstrap.servers=localhost:19093
listeners=CONTROLLER://localhost:19093
controller.listener.names=CONTROLLER
log.dirs=$D/c0
EOF
"$KAFKA_HOME/bin/kafka-storage.sh" format -t "$CID" -c "$D/c0.properties" --standalone >/dev/null
for i in 1 2 3; do
  cat > "$D/b$i.properties" <<EOF
process.roles=broker
node.id=$i
controller.quorum.bootstrap.servers=localhost:19093
controller.listener.names=CONTROLLER
listeners=PLAINTEXT://localhost:${PORTS[$((i-1))]}
advertised.listeners=PLAINTEXT://localhost:${PORTS[$((i-1))]}
inter.broker.listener.name=PLAINTEXT
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
log.dirs=$D/b$i
offsets.topic.replication.factor=3
transaction.state.log.replication.factor=3
share.coordinator.state.topic.replication.factor=3
# 其余全是默认值：unclean.leader.election.enable=false、replica.lag.time.max.ms=30000、broker.session.timeout.ms=9000
EOF
  "$KAFKA_HOME/bin/kafka-storage.sh" format -t "$CID" -c "$D/b$i.properties" --no-initial-controllers >/dev/null
done
KAFKA_HEAP_OPTS="-Xmx256m -Xms256m" LOG_DIR=$D/logs/c0 nohup "$KAFKA_HOME/bin/kafka-server-start.sh" "$D/c0.properties" >/dev/null 2>&1 &
echo $! > "$D/c0.pid"
for i in 1 2 3; do start_broker $i; done
until "$KAFKA_HOME/bin/kafka-broker-api-versions.sh" --bootstrap-server $BS 2>/dev/null | grep -c 'id: ' | grep -q 3; do sleep 2; done
say "ELR 特性：$("$KAFKA_HOME/bin/kafka-features.sh" --bootstrap-server $BS describe 2>/dev/null | grep -o 'Feature: eligible.leader.replicas.version.*FinalizedVersionLevel: [0-9]*' | tr -s '\t ' ' ')"
"$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server $BS --create --topic order-events \
  --partitions 6 --replication-factor 3 --config min.insync.replicas=2 >/dev/null
sleep 2

# ---------- 共同的前半段：一台一台倒下 ----------
line=$(p3 $BS); L=$(echo "$line" | sed -E 's/.*Leader: ([0-9]+).*/\1/')
F=($(echo "$line" | sed -E 's/.*Replicas: ([0-9,]+).*/\1/' | tr ',' '\n' | grep -vx "$L"))
F1=${F[0]}; F2=${F[1]}
say "剧本 $SCENARIO。leader=$L，follower $F1（待会儿最先倒、从此落后）和 $F2"; show
drill points                      # 积分服务先入组（此时分区是空的，位点记 0）
drill send A 10;  show
say "kill -9 broker $F1"; kill9 $F1; wait_isr "$L,$F2"; show
drill send B 10
drill points                      # 积分服务处理完 A、B，把位点提交到 20
if [ "$SCENARIO" = elr-clean ]; then say "正常关机 broker $F2（SIGTERM，受控关闭）"; term $F2
else say "kill -9 broker $F2"; kill9 $F2; fi
wait_isr "$L"; show
drill send C 1                    # ISR 只剩 1 个 < min.insync.replicas=2：应当写不进
say "kill -9 broker $L（leader）"; kill9 $L; sleep 12; show

# ---------- 后半段：谁先回来 ----------
case $SCENARIO in
laggard)
  say "只有 broker $F1 回来了（它没有 B 批）"; start_broker $F1; sleep 15; show
  drill send D 1
  say "等不起了：对分区 3 做 unclean 选举"
  "$KAFKA_HOME/bin/kafka-leader-election.sh" --bootstrap-server localhost:${PORTS[$((F1-1))]} \
    --election-type unclean --topic order-events --partition 3 2>&1 | sed 's/^/   /'
  sleep 3; show
  drill read
  drill send D 1
  say "还是不能写：ISR 只有 1 个 < min.insync.replicas=2，高水位也不前进。临时把 min.insync.replicas 调成 1"
  "$KAFKA_HOME/bin/kafka-configs.sh" --bootstrap-server localhost:${PORTS[$((F1-1))]} --alter --entity-type topics \
    --entity-name order-events --add-config min.insync.replicas=1 2>&1 | sed 's/^/   /'
  sleep 3
  drill read
  drill send D 3
  say "broker $L、$F2 也回来了"; start_broker $L; start_broker $F2; wait_isr "$L,$F1,$F2"; show
  "$KAFKA_HOME/bin/kafka-configs.sh" --bootstrap-server $BS --alter --entity-type topics \
    --entity-name order-events --add-config min.insync.replicas=2 >/dev/null 2>&1; say "min.insync.replicas 改回 2"
  drill read
  say "broker $L 的日志里，offset 10 现在是什么："
  "$KAFKA_HOME/bin/kafka-dump-log.sh" --files "$D/b$L/order-events-3/00000000000000000000.log" --print-data-log 2>/dev/null \
    | grep -oE 'offset: 1[0-2] .*payload: .*' | sed -E 's/ (CreateTime|keySize|valueSize|sequence|headerKeys)[^ ]*//g' | cut -c1-160 | sed 's/^/   /'
  grep -h "Truncating\|truncat" "$D/logs/b$L/server.log" | grep 'order-events-3' | tail -2 | cut -c1-260 | sed 's/^/   /'
  drill points
  ;;
elr-kill|elr-clean)
  say "broker $F2 先回来（它有 A、B 两批）"; start_broker $F2; sleep 15; show
  drill read
  drill send D 1
  say "broker $L 也回来了"; start_broker $L; sleep 15; show
  drill read
  say "最后 broker $F1 回来"; start_broker $F1; wait_isr "$L,$F1,$F2"; show
  drill points
  ;;
leader-first)
  say "broker $L（最后一任 leader，数据最全）先回来"; start_broker $L; sleep 15; show
  drill read
  say "broker $F2 也回来了"; start_broker $F2; sleep 15; show
  drill read
  say "最后 broker $F1 回来"; start_broker $F1; wait_isr "$L,$F1,$F2"; show
  ;;
esac
say "controller 日志里的选举记录："
grep -hE 'using ELR|unclean election|Triggering unclean' "$D"/logs/c0/*.log 2>/dev/null | grep -E 'partition 3\b|-3\b' \
  | sed -E 's/Previous partition.*//' | cut -c1-220 | sed 's/^/   /' || true
stop_all
say "完"
