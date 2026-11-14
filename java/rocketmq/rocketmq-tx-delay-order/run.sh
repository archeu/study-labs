#!/usr/bin/env bash
# RocketMQ 事务消息、定时消息、顺序消息：起一个单节点 RocketMQ 5.5.1（NameServer + Broker），跑 SpecialMessages。
# 用法：./run.sh                 起 RocketMQ，tx、delay、order 三种全跑一遍
#       ./run.sh tx              RocketMQ 留着，只跑一种（tx | delay | order orderly|concurrent|rr）
#       ./run.sh stop            停掉 RocketMQ
#       ./run.sh clean           停掉，并删掉 data/
# 需要 JDK 17 和 Maven。RocketMQ 包放在 ROCKETMQ_HOME，没有就下载到 ./data/ 下。
# 所有日志、存储都写在 ./data/ 里（user.home 也指到 data/home），不碰 ~/logs 和 ~/store。
set -euo pipefail
cd "$(dirname "$0")"
HERE=$(pwd); V=5.5.1; D=$HERE/data
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}
ROCKETMQ_HOME=${ROCKETMQ_HOME:-$D/rocketmq-all-$V-bin-release}
NS=127.0.0.1:19876

# mqnamesrv、mqbroker 是两层 shell 包着 java，按命令行里的配置文件路径找到整条链一起停
running() { pgrep -f -- "-c $D/$1.conf" > /dev/null; }
stop() {
  for n in broker namesrv; do
    running $n || continue
    pkill -f -- "-c $D/$n.conf" || true
    for _ in $(seq 30); do running $n || break; sleep 1; done
    pkill -9 -f -- "-c $D/$n.conf" || true
  done
}
case "${1:-}" in
  stop) stop; exit 0 ;;
  # 启动脚本还会把 GC 日志写到 /dev/shm/rmq_srv_gc_*.log
  clean) stop; rm -rf "$D"; rm -f /dev/shm/rmq_srv_gc_*.log; exit 0 ;;
esac

start() {
  mkdir -p "$D/home"
  if [ ! -d "$ROCKETMQ_HOME" ]; then
    (cd "$D" && curl -sfSLO https://dlcdn.apache.org/rocketmq/$V/rocketmq-all-$V-bin-release.zip \
      && unzip -q rocketmq-all-$V-bin-release.zip && rm rocketmq-all-$V-bin-release.zip)
  fi
  # 默认脚本给 NameServer 4g、Broker 8g 堆和 15g 直接内存，本机演示改小
  sed -i 's/-Xms4g -Xmx4g -Xmn2g/-Xms256m -Xmx256m/; s/-Xms4g -Xmx4g/-Xms256m -Xmx256m/' "$ROCKETMQ_HOME/bin/runserver.sh"
  sed -i 's/-Xms8g -Xmx8g/-Xms1g -Xmx1g/; s/MaxDirectMemorySize=15g/MaxDirectMemorySize=1g/; s/-Xmn4g //' "$ROCKETMQ_HOME/bin/runbroker.sh"
  export JAVA_OPT_EXT="-Duser.home=$D/home"
  if ! running namesrv; then
    echo "listenPort=19876" > "$D/namesrv.conf"
    nohup "$ROCKETMQ_HOME/bin/mqnamesrv" -c "$D/namesrv.conf" > "$D/namesrv.out" 2>&1 &
    sleep 3
  fi
  if ! running broker; then
    cat > "$D/broker.conf" <<P
brokerClusterName=DefaultCluster
brokerName=broker-a
brokerId=0
brokerIP1=127.0.0.1
namesrvAddr=$NS
listenPort=19911
storePathRootDir=$D/store
storePathCommitLog=$D/store/commitlog
# CommitLog 文件默认 1 GB 一个，演示改成 100 MB
mappedFileSizeCommitLog=104857600
# 事务回查、定时消息的参数都用默认值：transactionTimeOut=6000、transactionCheckInterval=30000、
# transactionCheckMax=15、timerWheelEnable=true、timerPrecisionMs=1000、timerMaxDelaySec=259200
P
    nohup "$ROCKETMQ_HOME/bin/mqbroker" -c "$D/broker.conf" > "$D/broker.out" 2>&1 &
  fi
  for _ in $(seq 60); do
    "$ROCKETMQ_HOME/bin/mqadmin" clusterList -n $NS 2>/dev/null | grep -q broker-a && break; sleep 1
  done
  for t in order-paid:4 order-cancel-check:4 order-status:8; do
    "$ROCKETMQ_HOME/bin/mqadmin" updateTopic -n $NS -c DefaultCluster -t "${t%%:*}" -r "${t##*:}" -w "${t##*:}" > /dev/null
  done
}

start
MVN="mvn -q -f ../../pom.xml -pl rocketmq/rocketmq-tx-delay-order"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
J="$JAVA_HOME/bin/java -Duser.home=$D/home -Ddb=$D/orders -cp $CP labs.rocketmq.rocketmq_tx_delay_order.SpecialMessages"
if [ $# -ge 1 ]; then $J "$@"; exit; fi
$J tx
$J delay
for m in orderly concurrent rr; do $J order $m; done
