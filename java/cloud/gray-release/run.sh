#!/bin/bash
# 灰度发布与流量治理实验。用法：
#   ./run.sh up      起 Nacos 3.3.0 容器（和 nacos-registry-config 同一个，已在跑就跳过）
#   ./run.sh demo    灰度路由、名单热更新、标记透传、hint 回退、两种发版对比（约 5 分钟）
#   ./run.sh down    停掉本实验的进程（加 all 连容器一起删）
set -u
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
J=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}/bin/java
JAR=target/cloud-gray-release-1.0.jar
mkdir -p data

up() { (cd ../nacos-registry-config && DOCKER="$DOCKER" ./run.sh up); }
cls() { local c=$1; shift; $J -cp $JAR -Dloader.main=labs.cloud.gray_release.$c org.springframework.boot.loader.launch.PropertiesLauncher "$@" 2>/dev/null | grep -v ' INFO \| WARN \|ERROR\|^\s*at \|^$'; }
svc() {  # svc <名字> <版本> <端口> [propagate=false]
  (nohup $J -Xmx96m -cp $JAR -Dloader.main=labs.cloud.gray_release.Svc org.springframework.boot.loader.launch.PropertiesLauncher "$@" > data/svc-$3.log 2>&1 & echo $! > data/svc-$3.pid)
}
kill_svc() { kill ${2:-} $(cat data/svc-$1.pid) 2>/dev/null; rm -f data/svc-$1.pid; }
publish() { cls Tool publish gateway.yaml "$1" | grep -o 'publish.*'; }
hit() { cls Tool hit "$@"; }

demo() {
  [ -f $JAR ] || (cd ../.. && mvn -q -pl cloud/gray-release package -DskipTests)
  publish 'gray:\n  users: ["1024"]'
  svc inventory-svc v1 18111; svc inventory-svc v2 18112
  svc order-svc v1 18101; svc order-svc v1 18102; svc order-svc v2 18103
  (nohup $J -Xmx256m -jar $JAR > data/gw.log 2>&1 & echo $! > data/gw.pid)
  until curl -s -o /dev/null localhost:18070/actuator/health; do sleep 0.5; done
  sleep 5

  echo "== ① 灰度名单 [1024]：各下单 10 次"
  echo "  用户 1024："; hit 1024 10
  echo "  用户 2048："; hit 2048 10
  echo "  2048 自己带 X-Gray: v2 想混进灰度："; curl -s -H 'X-User-Id: 2048' -H 'X-Gray: v2' localhost:18070/api/orders/9527; echo

  echo "== ② 在 Nacos 把 2048 加进名单，不重启网关"
  line=$(cls Tool publish gateway.yaml 'gray:\n  users: ["1024", "2048"]' | grep publish); t=${line%% *}
  until curl -s -H 'X-User-Id: 2048' localhost:18070/api/orders/9527 | grep -q 'order v2'; do sleep 0.05; done
  echo "  2048 第一次落到 v2：发布成功后 $(( $(date +%s%3N) - t )) ms"
  publish 'gray:\n  users: ["1024"]' > /dev/null; sleep 2

  echo "== ③ order v2 忘了往下传灰度标记"
  kill_svc 18103; svc order-svc v2 18103 propagate=false; sleep 4
  echo "  用户 1024："; hit 1024 10
  kill_svc 18103; svc order-svc v2 18103; sleep 4

  echo "== ④ inventory v2 下线了：灰度请求会怎样"
  kill_svc 18112 -9; sleep 3
  echo "  用户 1024："; hit 1024 6
  echo "  order v2 也挂了（kill -9），马上下单："; kill_svc 18103 -9; hit 1024 6
  sleep 36; echo "  36 秒后（网关的实例缓存过期）："; hit 1024 6
  svc inventory-svc v2 18112; svc order-svc v2 18103

  echo "== ⑤ 发版 order v1 18102：直接 kill -9（用户 2048，每秒 20 单，第 5 秒 kill）"
  ( sleep 5; kill_svc 18102 -9 ) &
  cls Tool load 2048 40 20
  wait
  svc order-svc v1 18102; echo "  等 40 秒，让网关缓存里重新有 18102"; sleep 40
  echo "== ⑥ 发版 order v1 18102：先下线、等 40 秒、再停"
  ( sleep 5; curl -s -X POST localhost:18102/admin/offline > /dev/null; sleep 40; kill_svc 18102 ) &
  cls Tool load 2048 55 20
  wait
  down_apps
}

down_apps() { for f in data/*.pid; do [ -f "$f" ] && kill $(cat "$f") 2>/dev/null; rm -f "$f"; done; }
down() { down_apps; [ "${1:-}" = all ] && $DOCKER rm -f j10-nacos >/dev/null 2>&1; echo "已清理"; }

"${1:-demo}" "${@:2}"
