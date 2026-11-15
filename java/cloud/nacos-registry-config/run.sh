#!/bin/bash
# Nacos 注册发现与配置中心实验。用法：
#   ./run.sh up      起 Nacos 3.3.0 单机容器并设置管理员密码（第一次约 30 秒）
#   ./run.sh demo    注册、配置推送、三种“实例出事”掐表（约 2 分钟）
#   ./run.sh down    停掉本实验起的进程和容器
# 需要：JDK 17、Maven、Docker（DOCKER="sudo docker" ./run.sh up）
set -u
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
J=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}/bin/java
JAR=target/cloud-nacos-registry-config-1.0.jar
PASS=LabPass123
mkdir -p data
now() { date +%s%3N; }

up() {
  $DOCKER rm -f j10-nacos >/dev/null 2>&1
  # 3.x 镜像要求这三个变量；端口只绑本机。客户端的 gRPC 端口 = 主端口 + 1000
  $DOCKER run -d --name j10-nacos -e MODE=standalone \
    -e NACOS_AUTH_TOKEN="$(head -c 32 /dev/urandom | base64)" \
    -e NACOS_AUTH_IDENTITY_KEY=labKey -e NACOS_AUTH_IDENTITY_VALUE=labValue \
    -e JVM_XMS=512m -e JVM_XMX=512m -e JVM_XMN=256m \
    -p 127.0.0.1:18848:8848 -p 127.0.0.1:19848:9848 -p 127.0.0.1:18080:8080 \
    nacos/nacos-server:v3.3.0 >/dev/null
  t0=$(now)
  until curl -sf -o /dev/null http://127.0.0.1:18848/nacos/v3/admin/core/state; do sleep 1; done
  echo "Nacos 就绪：$(( ($(now)-t0)/1000 )) s"
  # 3.3.0 镜像默认开鉴权：第一次要给 nacos 用户设密码
  curl -s -X POST http://127.0.0.1:18848/nacos/v3/auth/user/admin -d "password=$PASS"; echo
}

watcher() { $J -cp $JAR -Dloader.main=labs.cloud.nacos_registry_config.Watcher org.springframework.boot.loader.launch.PropertiesLauncher "$@"; }
start_inv() {  # $1 = 1 或 2
  # 放进子 shell：kill -9 时 bash 不会打印 “Killed”
  (PORT=$((18080 + $1)) nohup $J -Xmx256m -jar $JAR > data/inv-$1.log 2>&1 & echo $! > data/inv-$1.pid)
}
# 等 watcher 日志里出现“时间戳 > $1 且实例数为 $2”的推送，打印从 $1 起过了多久
wait_push() {
  local after=$1 n=$2 line
  for _ in $(seq 1 900); do
    line=$(awk -v a="$after" -v n="[$n]" '$1>a && $3=="PUSH" && $5==n {print; exit}' data/watch.log)
    if [ -n "$line" ]; then echo "$(( ${line%% *} - after )) ms  ← $(echo "$line" | cut -d' ' -f2-)"; return; fi
    sleep 0.1
  done
  echo "90 s 内没有推送"
}

demo() {
  [ -f $JAR ] || (cd ../.. && mvn -q -pl cloud/nacos-registry-config package -DskipTests)
  watcher publish inventory-svc.yaml 'inventory:\n  timeout: 2000' 2>/dev/null | grep -E '^[0-9]+ .* publish '
  watcher watch 2>/dev/null | grep --line-buffered -E 'PUSH|CONFIG|watching' > data/watch.log &
  sleep 4

  echo "== ① 启动两个库存实例，订阅方多久看到"
  t=$(now); start_inv 1; start_inv 2
  echo -n "看到 2 个实例："; wait_push $t 2
  grep -h 'Started InventoryApp' data/inv-*.log | sed 's/.*Started/  Started/'

  echo "== ② 配置中心：inventory.timeout 2000 → 800，不重启"
  echo "  改之前 /cfg：$(curl -s localhost:18081/cfg)"
  # 发布工具本身要起一个 JVM（约 2 秒），所以从它打印的“发布成功”时刻开始算
  line=$(watcher publish inventory-svc.yaml 'inventory:\n  timeout: 800' 2>/dev/null | grep -E '^[0-9]+ .* publish '); t=${line%% *}
  until curl -s localhost:18081/cfg | grep -q '"byProperties":800'; do sleep 0.02; done
  echo "  @ConfigurationProperties 变成 800：发布成功后 $(( $(now)-t )) ms"
  c=$(awk -v a=$t '$1>=a-50 && $3=="CONFIG" && /800/ {print $1; exit}' data/watch.log)
  echo "  订阅方收到新配置：发布成功后 $(( c-t )) ms"
  grep -h 'Refresh keys changed' data/inv-1.log | tail -1 | sed 's/.*Refresh/  inv-1 日志：Refresh/'
  sleep 1; echo "  改之后 /cfg：$(curl -s localhost:18081/cfg)   ← @Value 那个没变"

  echo "== ③ inventory-2 变慢（每次 3 s），Nacos 怎么看"
  curl -s -X POST 'localhost:18082/chaos/slow?ms=3000' >/dev/null
  t=$(now); curl -s -o /dev/null -X POST 'localhost:18082/stock/reserve?orderId=9527&sku=88&qty=2'; echo "  调一次 reserve：$(( $(now)-t )) ms"
  t=$(now); sleep 30
  echo "  之后 30 s 里推送次数：$(awk -v a=$t '$1>a && $3=="PUSH"' data/watch.log | wc -l)（仍是 2 个健康实例）"
  curl -s -X POST 'localhost:18082/chaos/slow?ms=0' >/dev/null

  echo "== ④ 主动下线 inventory-2（actuator serviceregistry → DOWN）"
  t=$(now); curl -s -X POST localhost:18082/actuator/serviceregistry -H 'Content-Type: application/json' -d '{"status":"DOWN"}'
  echo -n "  订阅方只剩 1 个："; wait_push $t 1
  t=$(now); curl -s -X POST localhost:18082/actuator/serviceregistry -H 'Content-Type: application/json' -d '{"status":"UP"}'
  echo -n "  再 UP 回来："; wait_push $t 2

  echo "== ⑤ kill -9 inventory-2"
  t=$(now); kill -9 $(cat data/inv-2.pid)
  echo -n "  订阅方只剩 1 个："; wait_push $t 1

  echo "== ⑥ 重启 inventory-2，然后 kill -STOP（进程冻住，TCP 连接还在）"
  t=$(now); start_inv 2; echo -n "  重新注册："; wait_push $t 2
  sleep 2
  t=$(now); kill -STOP $(cat data/inv-2.pid)
  echo -n "  订阅方只剩 1 个："; wait_push $t 1
  t=$(now); kill -CONT $(cat data/inv-2.pid)
  echo -n "  kill -CONT 以后自己回来："; wait_push $t 2

  echo "== ⑦ 正常停机：kill（SIGTERM），Spring 关闭时先注销"
  t=$(now); kill $(cat data/inv-2.pid)
  echo -n "  订阅方只剩 1 个："; wait_push $t 1
  down_apps
}

down_apps() {
  for f in data/inv-*.pid; do [ -f "$f" ] && kill $(cat "$f") 2>/dev/null; rm -f "$f"; done
  ps -eo pid,args | grep '[l]oader.main=labs.cloud.nacos_registry_config.Watcher' | awk '{print $1}' | xargs -r kill
}
down() { down_apps; $DOCKER rm -f j10-nacos >/dev/null 2>&1; echo "已清理"; }

"${1:-demo}"
