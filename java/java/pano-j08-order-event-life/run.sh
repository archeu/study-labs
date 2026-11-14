#!/usr/bin/env bash
# 全景 J08：一条订单消息的一生。起 Kafka 4.4.0（KRaft：1 个 controller + 3 个 broker），
# 建 order-events（6 分区、3 副本、min.insync.replicas=2），跑 OrderEventLife 的几段。
# 用法：./run.sh          全部跑一遍：起集群 → trace → dump（看日志段）→ isr（停副本）→ dup（重复投递）
#       ./run.sh trace    集群留着，只跑某一段（trace / dump / isr / dup）
#       ./run.sh stop     停掉 4 个 Kafka 进程
#       ./run.sh clean    停掉并删掉 data/ logs/
# 需要 JDK 17+ 和 Maven。Kafka 包放在 KAFKA_HOME，没有就下载到 ./data/ 下。
set -euo pipefail
cd "$(dirname "$0")"
HERE=$(pwd)
V=4.4.0
KAFKA_HOME=${KAFKA_HOME:-$HERE/data/kafka_2.13-$V}
D=$HERE/data
PORTS=(19092 29092 39092)

stop() {  # 先停 broker（它们要向 controller 报告受控关闭），再停 controller；等进程真的退出
  for n in b1 b2 b3 c0; do
    f=$D/$n.pid; [ -f "$f" ] || continue
    pid=$(cat "$f"); kill "$pid" 2>/dev/null || true
    for _ in $(seq 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$pid" 2>/dev/null || true; rm -f "$f"
  done
}
case "${1:-}" in
  stop) stop; exit 0 ;;
  clean) stop; rm -rf "$D" "$HERE/logs"; exit 0 ;;
esac

MVN="mvn -q -f ../../pom.xml -pl java/pano-j08-order-event-life"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="java -cp $CP labs.java.pano_j08_order_event_life.OrderEventLife"
BS=localhost:19092,localhost:29092,localhost:39092
leader_of() {  # 打印 order-events 某分区的 leader、replicas、isr
  "$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server $BS --describe --topic order-events | grep -E "Partition: $1\b"
}

dump() {
  echo "== dump：分区目录里的文件，和 9527 那批消息在 .log 里的样子（broker 1 的副本）"
  part=$(ls -d "$D"/b1/order-events-* | head -1)
  for dir in "$D"/b1/order-events-*; do
    if "$KAFKA_HOME/bin/kafka-dump-log.sh" --files "$dir/00000000000000000000.log" --print-data-log 2>/dev/null | grep -q '"orderId":9527'; then part=$dir; fi
  done
  ls -l "$part" | awk 'NR>1{printf "   %-28s %8s\n",$9,$5}'
  "$KAFKA_HOME/bin/kafka-dump-log.sh" --files "$part/00000000000000000000.log" --print-data-log 2>/dev/null \
    | grep -B1 '"orderId":9527' | grep -v '^--' | head -4 | cut -c1-400
}

isr() {
  echo "== isr：订单 9527 所在分区，逐个停掉 follower"
  pnum=$(python3 - <<'EOF'
# murmur2("9527") 的结果在 trace 里打印过；这里复算一遍，免得再起 JVM
def murmur2(data):
    length=len(data); seed=0x9747b28c; m=0x5bd1e995; r=24
    h=(seed ^ length) & 0xffffffff
    l4=length//4
    for i in range(l4):
        i4=i*4
        k=(data[i4+0]&0xff)+((data[i4+1]&0xff)<<8)+((data[i4+2]&0xff)<<16)+((data[i4+3]&0xff)<<24)
        k=(k*m)&0xffffffff; k^=k>>r; k=(k*m)&0xffffffff
        h=(h*m)&0xffffffff; h^=k
    rem=length%4
    if rem==3: h^=(data[(length&~3)+2]&0xff)<<16
    if rem>=2: h^=(data[(length&~3)+1]&0xff)<<8
    if rem>=1:
        h^=data[length&~3]&0xff; h=(h*m)&0xffffffff
    h^=h>>13; h=(h*m)&0xffffffff; h^=h>>15
    return h
print((murmur2(b"9527") & 0x7fffffff) % 6)
EOF
)
  line=$(leader_of "$pnum"); echo "   $line" | tr -s '\t ' ' '
  lead=$(echo "$line" | sed -E 's/.*Leader: ([0-9]+).*/\1/')
  followers=$(echo "$line" | sed -E 's/.*Replicas: ([0-9,]+).*/\1/' | tr ',' '\n' | grep -vx "$lead")
  echo "   三个副本都在："; $J send 9527
  n=0
  for f in $followers; do
    n=$((n+1)); kill "$(cat "$D/b$f.pid")"; rm -f "$D/b$f.pid"
    echo "   停掉 broker $f（follower），等 ISR 把它踢出去……"; sleep 35
    echo "   $(leader_of "$pnum" | tr -s '\t ' ' ')"
    echo "   只剩 $((3-n)) 个副本时发一条："; $J send 9527
  done
  echo "   把两个 broker 拉起来"
  for f in $followers; do KAFKA_HEAP_OPTS="-Xmx512m -Xms512m" start_broker "$f"; done
  sleep 15; echo "   $(leader_of "$pnum" | tr -s '\t ' ' ')"
}

start_broker() {
  local i=$1
  LOG_DIR=$HERE/logs/b$i nohup "$KAFKA_HOME/bin/kafka-server-start.sh" "$D/b$i.properties" >/dev/null 2>&1 &
  echo $! > "$D/b$i.pid"
}

if [ -n "${1:-}" ]; then
  case $1 in dump) dump ;; isr) isr ;; *) $J "$1" ;; esac
  exit 0
fi

# ---------- 起集群 ----------
mkdir -p "$D" "$HERE/logs"
if [ ! -x "$KAFKA_HOME/bin/kafka-server-start.sh" ]; then
  (cd "$D" && curl -sfSLO https://downloads.apache.org/kafka/$V/kafka_2.13-$V.tgz && tar xzf kafka_2.13-$V.tgz && rm kafka_2.13-$V.tgz)
fi
stop
rm -rf "$D"/c0 "$D"/b1 "$D"/b2 "$D"/b3
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
transaction.state.log.min.isr=2
share.coordinator.state.topic.replication.factor=3
share.coordinator.state.topic.min.isr=2
# 页面上的其余数字都是默认值：段 1 GiB、保留 168 小时、replica.lag.time.max.ms=30000
EOF
  "$KAFKA_HOME/bin/kafka-storage.sh" format -t "$CID" -c "$D/b$i.properties" --no-initial-controllers >/dev/null
done
KAFKA_HEAP_OPTS="-Xmx256m -Xms256m" LOG_DIR=$HERE/logs/c0 nohup "$KAFKA_HOME/bin/kafka-server-start.sh" "$D/c0.properties" >/dev/null 2>&1 &
echo $! > "$D/c0.pid"
export KAFKA_HEAP_OPTS="-Xmx512m -Xms512m"
for i in 1 2 3; do start_broker $i; done
until "$KAFKA_HOME/bin/kafka-broker-api-versions.sh" --bootstrap-server $BS 2>/dev/null | grep -c 'id: ' | grep -q 3; do sleep 2; done
"$KAFKA_HOME/bin/kafka-topics.sh" --bootstrap-server $BS --create --topic order-events \
  --partitions 6 --replication-factor 3 --config min.insync.replicas=2 >/dev/null
sleep 2

$J trace
dump
echo "== 消费组位点（kafka-consumer-groups.sh）"
for g in inventory-svc points-svc notify-svc; do
  "$KAFKA_HOME/bin/kafka-consumer-groups.sh" --bootstrap-server $BS --describe --group $g 2>/dev/null | awk -v g=$g '$2=="order-events" {c+=$4; e+=$5; l+=$6} END{printf "   %-14s 已提交位点合计 %d，日志末尾合计 %d，LAG %d\n", g, c, e, l}'
done
isr
$J dup
