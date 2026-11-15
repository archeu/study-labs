#!/bin/bash
# Spring Cloud Gateway 实验。用法：
#   ./run.sh up      起 Nacos 3.3.0 容器（和 nacos-registry-config 同一个，已在跑就跳过）
#   ./run.sh demo    鉴权与改头、路由、阻塞过滤器压测、响应超时（约 2 分钟）
#   ./run.sh down    停掉本实验的进程（加 all 连容器一起删）
set -u
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
J=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}/bin/java
JAR=target/cloud-gateway-route-auth-1.0.jar
GW=http://127.0.0.1:18070
mkdir -p data

up() { (cd ../nacos-registry-config && DOCKER="$DOCKER" ./run.sh up); }
run_main() { local cls=$1; shift; $J -cp $JAR -Dloader.main=labs.cloud.gateway_route_auth.$cls org.springframework.boot.loader.launch.PropertiesLauncher "$@" 2>/dev/null | grep -v ' INFO \| WARN '; }
start_gw() {
  (nohup $J -Xmx256m -jar $JAR "$@" > data/gw.log 2>&1 & echo $! > data/gw.pid)
  until curl -s -o /dev/null $GW/actuator/health; do sleep 0.5; done
  sleep 2
}
stop_gw() { kill $(cat data/gw.pid); sleep 2; }

demo() {
  [ -f $JAR ] || (cd ../.. && mvn -q -pl cloud/gateway-route-auth package -DskipTests)
  (nohup $J -cp $JAR -Dloader.main=labs.cloud.gateway_route_auth.OrderBackend org.springframework.boot.loader.launch.PropertiesLauncher 18091 > data/backend.log 2>&1 & echo $! > data/backend.pid)
  start_gw
  T=$(run_main Jwt 1024 3600)
  OLD=$($J -cp $JAR -Dloader.main=labs.cloud.gateway_route_auth.Jwt org.springframework.boot.loader.launch.PropertiesLauncher 1024 -10 2>/dev/null | tail -1)

  echo "== ① 鉴权：没带 token / 过期 token / 伪造签名"
  echo "  没带：$(curl -s -o /dev/null -w '%{http_code}' $GW/api/orders/9527)"
  echo "  过期：$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $OLD" $GW/api/orders/9527)"
  echo "  改签名：$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${T%.*}.AAAA" $GW/api/orders/9527)"
  echo "== ② 正常：路由 + StripPrefix + 网关写上 X-User-Id"
  echo "  $(curl -s -H "Authorization: Bearer $T" $GW/api/orders/9527)"
  echo "== ③ 冒充：客户端自己带 X-User-Id: 2048"
  echo "  $(curl -s -H "Authorization: Bearer $T" -H 'X-User-Id: 2048' $GW/api/orders/9527)"
  echo "  没有路由的路径：$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $T" $GW/api/nothing)"

  echo "== ④ 过滤器里“查 Redis 要 200 ms”：阻塞 vs 非阻塞（200 个请求，并发 50）"
  echo "  CPU 核数 $(nproc)；处理请求的线程（连发 40 次，看响应头 X-Gw-Thread）："
  for i in $(seq 1 40); do curl -s -D - -o /dev/null -H "Authorization: Bearer $T" $GW/api/orders/9527 & done | grep -i x-gw-thread | sort | uniq -c | sed 's/^/   /'; wait
  run_main Load $GW/api/orders/9527 none 400 50 > /dev/null   # 预热 JIT
  run_main Load $GW/api/orders/9527 none 200 50
  run_main Load $GW/api/orders/9527 async 200 50
  run_main Load $GW/api/orders/9527 block 200 50

  echo "== ⑤ 后端卡 5 s：网关默认不设响应超时"
  t=$(date +%s%3N); c=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $T" $GW/api/orders/slow)
  echo "  默认：$c，用了 $(( $(date +%s%3N)-t )) ms"
  stop_gw; start_gw --spring.cloud.gateway.server.webflux.httpclient.response-timeout=3s
  t=$(date +%s%3N); c=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $T" $GW/api/orders/slow)
  echo "  response-timeout=3s：$c，用了 $(( $(date +%s%3N)-t )) ms"
  down_apps
}

down_apps() { for f in data/*.pid; do [ -f "$f" ] && kill $(cat "$f") 2>/dev/null; rm -f "$f"; done; }
down() { down_apps; [ "${1:-}" = all ] && $DOCKER rm -f j10-nacos >/dev/null 2>&1; echo "已清理"; }

"${1:-demo}" "${@:2}"
