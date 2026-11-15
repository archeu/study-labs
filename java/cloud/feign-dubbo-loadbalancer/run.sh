#!/bin/bash
# OpenFeign + Spring Cloud LoadBalancer 实验。用法：
#   ./run.sh up      起 Nacos 3.3.0 容器（和 nacos-registry-config 实验同一个，已在跑就跳过）
#   ./run.sh demo    调用栈、轮询分布、下线后 LoadBalancer 缓存掐表（约 3 分钟）
#   ./run.sh down    停掉本实验的进程（加 all 连容器一起删）
set -u
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
J=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}/bin/java
JAR=target/cloud-feign-dubbo-loadbalancer-1.0.jar
mkdir -p data

up() { (cd ../nacos-registry-config && DOCKER="$DOCKER" ./run.sh up); }

start_inv() {  # $1 = 1 或 2，端口 18081 / 18082
  (PORT=$((18080 + $1)) nohup $J -Xmx256m -cp $JAR -Dloader.main=labs.cloud.feign_dubbo_loadbalancer.inventory.InventoryStub \
     org.springframework.boot.loader.launch.PropertiesLauncher > data/inv-$1.log 2>&1 & echo $! > data/inv-$1.pid)
}
start_order() {  # $1 = LoadBalancer 缓存 ttl
  (LB_TTL=$1 nohup $J -Xmx256m -jar $JAR > data/order.log 2>&1 & echo $! > data/order.pid)
  until curl -sf -o /dev/null localhost:18090/actuator/health 2>/dev/null || curl -sf -o /dev/null localhost:18090/demo/burst?n=1; do sleep 0.5; done
}
stop_order() { kill $(cat data/order.pid) 2>/dev/null; sleep 2; }
status() { curl -s -X POST localhost:$1/actuator/serviceregistry -H 'Content-Type: application/json' -d "{\"status\":\"$2\"}"; }

cache_run() {  # 跑 50 秒，第 5 秒把 18082 设成 DOWN
  curl -s 'localhost:18090/demo/run?seconds=50' > data/run.txt &
  sleep 5; echo "  第 5 秒：18082 → DOWN（Nacos 约 0.6 s 推给订阅方）"; status 18082 DOWN
  wait
  sed 's/^/  /' data/run.txt
  last=$(grep '18082' data/run.txt | tail -1 | cut -c1-2)
  echo "  最后一次落到 18082：第 ${last} 秒"
  status 18082 UP; sleep 2
}

demo() {
  [ -f $JAR ] || (cd ../.. && mvn -q -pl cloud/feign-dubbo-loadbalancer package -DskipTests)
  start_inv 1; start_inv 2
  until curl -sf -o /dev/null -X POST 'localhost:18082/chaos/slow?ms=0' && curl -sf -o /dev/null -X POST 'localhost:18081/chaos/slow?ms=0'; do sleep 0.5; done
  start_order 35s
  sleep 3

  echo "== ① 一次 inventoryClient.reserve() 背后"
  curl -s localhost:18090/demo/once; echo

  echo "== ② 轮询：连调 200 次"
  curl -s 'localhost:18090/demo/burst?n=200'; echo
  echo "== ③ 18082 变慢 300 ms，再连调 200 次（轮询不看快慢）"
  curl -s -X POST 'localhost:18082/chaos/slow?ms=300' >/dev/null
  curl -s 'localhost:18090/demo/burst?n=200'; echo
  curl -s -X POST 'localhost:18082/chaos/slow?ms=0' >/dev/null

  echo "== ④ LoadBalancer 缓存 35 s（默认）：18082 下线后，还有多少请求落到它"
  cache_run
  echo "== ⑤ 把缓存 ttl 改成 2 s，重启订单服务，再来一次"
  stop_order; start_order 2s; sleep 3
  cache_run
  down_apps
}

down_apps() { for f in data/*.pid; do [ -f "$f" ] && kill $(cat "$f") 2>/dev/null; rm -f "$f"; done; }
down() { down_apps; [ "${1:-}" = all ] && $DOCKER rm -f j10-nacos >/dev/null 2>&1; echo "已清理"; }

"${1:-demo}" "${@:2}"
