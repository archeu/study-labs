#!/usr/bin/env bash
# 提交途中 kill -9 mysqld，看重启后 InnoDB 和 binlog 怎么对账。三次：
#   0  UPDATE 做完、还没 COMMIT 就 kill     → 活跃事务，靠 undo 回滚
#   A  COMMIT 已经 prepare、binlog 写到一半 → binlog 里没有完整的 Xid，回滚
#   B  COMMIT 时 binlog 刚写完整就 kill     → binlog 里有 Xid，提交
# 大事务：UPDATE orders SET qty = qty + 1 WHERE id <= 300000（30 万行，binlog 几十 MB，写盘要一会儿，好掐时机）
# kill 用宿主机上 mysqld 的 pid（容器里 mysqld 是 1 号进程，容器内部杀不掉它），所以要 sudo。
set -euo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
C=${C:-redo-mysql}
BIG="UPDATE orders SET qty = qty + 1 WHERE id <= 300000"
q() { $DOCKER exec $C mysql --protocol=TCP -h127.0.0.1 -uroot -proot -N -B trade -e "$1" 2>/dev/null; }
ready() { until q 'select 1' >/dev/null 2>&1; do sleep 1; done; }
binfile() { q "SHOW BINARY LOG STATUS" | cut -f1; }
binpos() { q "SHOW BINARY LOG STATUS" | cut -f2; }
hostpid() { $DOCKER inspect -f '{{.State.Pid}}' $C; }
qty() { q "SELECT qty FROM orders WHERE id=9527"; }
nlog() { $DOCKER logs $C 2>&1 | wc -l; }
restart() {   # 重新拉起，打印这次启动时和恢复有关的日志
  local from=$1
  $DOCKER start $C >/dev/null; ready
  $DOCKER logs $C 2>&1 | tail -n +"$from" | grep -E 'prepared|[Rr]oll|to undo|[Rr]ecover|Xid|[Tt]rim|[Tt]runcat|apply|redo log|[Cc]rash' \
    | grep -vE 'deprecated|SSL|X Plugin|ready for' | sed -E 's/^[0-9T:.-]+Z [0-9]+ //; s/^/      /' || true
}

ready
q "SET PERSIST log_error_verbosity = 3"   # 恢复过程的消息多是 Note 级，默认（2）不打
echo "[crash] 先正常跑一遍大事务，量一下它在 binlog 里有多大"
f=$(binfile); p0=$(binpos); t=$(date +%s%N)
q "$BIG"
D=$(( $(binpos) - p0 )); echo "  30 万行 COMMIT 用时 $(( ($(date +%s%N) - t) / 1000000 )) ms，binlog +$D 字节；9527 的 qty = $(qty)"

echo "[crash 0] UPDATE 做完、没 COMMIT 就 kill -9"
before=$(qty)
$DOCKER exec -d $C mysql --protocol=TCP -h127.0.0.1 -uroot -proot trade -e "BEGIN; $BIG; SELECT SLEEP(600);"
until [ "$(q 'SELECT IFNULL(MAX(trx_rows_modified),0) FROM information_schema.innodb_trx')" = 300000 ]; do sleep 0.2; done
from=$(( $(nlog) + 1 )); sudo kill -9 "$(hostpid)"; sleep 1
restart "$from"
echo "  9527 的 qty：kill 前 $before → 重启后 $(qty)"

for kase in A B; do
  ready
  f=$(binfile); p0=$(binpos); before=$(qty)
  if [ $kase = A ]; then target=$(( p0 + D / 3 )); what="binlog 写到三分之一"; else target=$(( p0 + D )); what="binlog 刚写完整"; fi
  echo "[crash $kase] COMMIT 途中，$what 就 kill -9（binlog $f 从 $p0 字节起，到 $target 字节时动手）"
  P=$(hostpid)
  $DOCKER exec -d $C mysql --protocol=TCP -h127.0.0.1 -uroot -proot trade -e "$BIG"
  from=$(( $(nlog) + 1 ))
  # 在宿主机上直接看容器里的 binlog 文件长度，一到目标就杀（先记下长度再杀，杀完 /proc/$P 就没了）
  sudo bash -c "F=/proc/$P/root/var/lib/mysql/$f; while s=\$(stat -c %s \$F) && [ \$s -lt $target ]; do :; done; kill -9 $P; echo \"  kill 时 binlog 长度 \$s\""
  sleep 1
  restart "$from"
  echo "  重启后 $f 长度 $(sudo stat -c %s /proc/$(hostpid)/root/var/lib/mysql/$f)；9527 的 qty：kill 前 $before → 重启后 $(qty)"
  q "SHOW BINLOG EVENTS IN '$f' FROM $p0" | awk -F'\t' '{print "      " $2 "  " $3 "  " substr($6,1,60)}' | tail -6
done
