#!/usr/bin/env bash
# 本机起一个最小的 TiDB 8.5.8 集群：1 PD + 3 TiKV + 2 TiDB server（127.0.0.1:4001、4002，MySQL 协议，root 无密码）
# 用法：cluster.sh up | down | status     本机不在 docker 组里：DOCKER="sudo docker" cluster.sh up
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
V=v8.5.8
NET=tidbnet
case "${1:-status}" in
up)
  $DOCKER network create $NET >/dev/null 2>&1 || true
  $DOCKER run -d --name pd --network $NET pingcap/pd:$V --name=pd1 --data-dir=/data \
    --client-urls=http://0.0.0.0:2379 --advertise-client-urls=http://pd:2379 \
    --peer-urls=http://0.0.0.0:2380 --advertise-peer-urls=http://pd:2380 --initial-cluster=pd1=http://pd:2380 >/dev/null
  for i in 1 2 3; do
    $DOCKER run -d --name tikv$i --network $NET -v "$PWD/tikv.toml:/conf/tikv.toml:ro" pingcap/tikv:$V \
      --addr=0.0.0.0:20160 --advertise-addr=tikv$i:20160 --status-addr=0.0.0.0:20180 --advertise-status-addr=tikv$i:20180 \
      --data-dir=/data --pd=pd:2379 --config=/conf/tikv.toml >/dev/null
  done
  for i in 1 2; do
    conf=tidb.toml; [ $i = 2 ] && conf=tidb-small-txn.toml      # tidb2 的单事务上限设成 10 MiB（第 7 页用）
    $DOCKER run -d --name tidb$i --network $NET -p 127.0.0.1:400$i:4000 -v "$PWD/$conf:/conf/tidb.toml:ro" pingcap/tidb:$V \
      --store=tikv --path=pd:2379 --advertise-address=tidb$i --config=/conf/tidb.toml >/dev/null
  done
  for i in $(seq 1 90); do
    if $DOCKER run --rm --network $NET mysql:8.4 mysql -h tidb1 -P 4000 -uroot -e 'select 1' >/dev/null 2>&1; then echo "TiDB ready after $((i*2)) s"; break; fi
    sleep 2
  done ;;
down)
  $DOCKER rm -fv tidb1 tidb2 tikv1 tikv2 tikv3 pd >/dev/null 2>&1 || true
  $DOCKER network rm $NET >/dev/null 2>&1 || true ;;
status)
  $DOCKER ps --filter network=$NET --format '{{.Names}} {{.Status}}' ;;
esac
