#!/usr/bin/env bash
# MySQL 到 ES 的同步：MySQL 8.4 容器（127.0.0.1:33114）+ ES 9.5.5 容器（127.0.0.1:19201）+ 本机跑的 Canal 1.1.8（TCP 11111）
#   第一次运行会把 canal.deployer-1.1.8.tar.gz 下到 data/（已 gitignore），用 canal-conf/orders 的配置
#   SyncLab e2e order outage：见类注释
#   purge：停 Canal → MySQL 清掉旧 binlog → 再启 Canal，看它从记住的位点续传时报什么
# 用法：./run.sh [new|again] [e2e|order|outage|purge|all] | ./run.sh clean
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
JAVA=${JAVA:-/usr/lib/jvm/java-17-openjdk-arm64/bin/java}
CANAL=data/canal
canal_stop() {
  if [ -f $CANAL/bin/canal.pid ]; then
    local p; p=$(cat $CANAL/bin/canal.pid); kill "$p" 2>/dev/null || true
    for _ in $(seq 1 30); do kill -0 "$p" 2>/dev/null || break; sleep 1; done
    rm -f $CANAL/bin/canal.pid
  fi
}
canal_start() {
  # 注意 $! 要是 java 自己的 pid：cd 单独一句，不要写成 “cd … && nohup java … &”（那样 $! 是子 shell）
  ( cd $CANAL/bin || exit 1
    nohup "$JAVA" -Xms256m -Xmx512m -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8 -DappName=otter-canal \
      -Dlogback.configurationFile=../conf/logback.xml -Dcanal.conf=../conf/canal.properties \
      -classpath "../conf:../lib/*" com.alibaba.otter.canal.deployer.CanalLauncher > ../logs/canal_stdout.log 2>&1 &
    echo $! > canal.pid )
  sleep 8
}
clean() { canal_stop; $DOCKER rm -fv sync-mysql sync-es >/dev/null 2>&1 || true; }
if [ "${1:-}" = clean ]; then clean; exit 0; fi

MVN="mvn -q -f ../../pom.xml -pl search/mysql-to-es-sync-canal"
$MVN compile
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes

if [ "${1:-}" != again ]; then
  clean
  if [ ! -d $CANAL/lib ]; then
    mkdir -p $CANAL
    curl -sfSL -o data/canal.deployer-1.1.8.tar.gz https://github.com/alibaba/canal/releases/download/canal-1.1.8/canal.deployer-1.1.8.tar.gz
    tar xzf data/canal.deployer-1.1.8.tar.gz -C $CANAL
  fi
  rm -rf $CANAL/conf/example $CANAL/conf/orders $CANAL/logs && mkdir -p $CANAL/logs && cp -r canal-conf/orders $CANAL/conf/orders
  sed -i 's/^canal.destinations = .*/canal.destinations = orders/; s/^canal.instance.tsdb.enable = .*/canal.instance.tsdb.enable = false/' $CANAL/conf/canal.properties
  $DOCKER run -d --name sync-mysql -p 127.0.0.1:33114:3306 -e MYSQL_ROOT_PASSWORD=root mysql:8.4 >/dev/null
  $DOCKER run -d --name sync-es -p 127.0.0.1:19201:9200 -e discovery.type=single-node -e xpack.security.enabled=false \
    -e "ES_JAVA_OPTS=-Xms1g -Xmx1g" elasticsearch:9.5.5 >/dev/null
  until $DOCKER exec sync-mysql mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'select 1' >/dev/null 2>&1; do sleep 2; done
  $DOCKER exec sync-mysql mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e "CREATE DATABASE order_db;
    CREATE USER canal IDENTIFIED BY 'canal'; GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO canal;
    CREATE TABLE order_db.t_order (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, shop_id BIGINT NOT NULL, title VARCHAR(64) NOT NULL,
      status VARCHAR(16) NOT NULL, version INT NOT NULL, amount_cents BIGINT NOT NULL, updated_at DATETIME(3) NOT NULL);" 2>/dev/null
  until curl -sf "http://127.0.0.1:19201/_cluster/health?wait_for_status=yellow&timeout=5s" >/dev/null; do sleep 2; done
  canal_start
  grep -h 'find start position successfully' $CANAL/logs/orders/orders.log | sed 's/.*WARN *//' | cut -c1-200 || true
fi
run() { "$JAVA" -cp "$CP" labs.search.mysql_to_es_sync_canal.SyncLab "$@"; }
case "${2:-all}" in
  e2e|order|outage) run "$2" ;;
  all) run all ;;
esac
if [ "${2:-all}" = purge ] || [ "${2:-all}" = all ]; then
  echo; echo "==== purge：停 Canal，MySQL 切一个新 binlog、把旧的都清掉，再启 Canal"
  canal_stop
  echo "   Canal 停着的时候，改 10 单的状态为 LOST_IN_PURGE（这些变更只在即将被清掉的 binlog 里）"
  $DOCKER exec sync-mysql mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e "UPDATE order_db.t_order SET status = 'LOST_IN_PURGE', version = version + 100 WHERE id BETWEEN 30001 AND 30010;" 2>/dev/null
  $DOCKER exec sync-mysql mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e "FLUSH BINARY LOGS; PURGE BINARY LOGS BEFORE NOW() + INTERVAL 1 SECOND; SHOW BINARY LOGS;" 2>/dev/null
  echo "   Canal 记住的位点（conf/orders/meta.dat）："; cut -c1-300 $CANAL/conf/orders/meta.dat; echo
  canal_start
  run probe
  echo -n "   ES 里那 10 单的状态："; curl -s "http://127.0.0.1:19201/orders_sync/_search?size=0&q=id:%5B30001%20TO%2030010%5D%20AND%20status:LOST_IN_PURGE" | grep -o '"value":[0-9]*' | head -1
  echo "   Canal 日志里的错误："
  grep -h -m2 -E 'ERROR|Could not find' $CANAL/logs/orders/orders.log | sed 's/^.*ERROR/ERROR/' | cut -c1-260 || true
fi
