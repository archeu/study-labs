#!/usr/bin/env bash
# MySQL 8.0 → 8.4 LTS：交易靶场 orders 库一主一从（GTID），按“先升从库、再切换、最后升旧主库”走一遍，
#   1  两台 8.0.46：老运维脚本（CHANGE MASTER / START SLAVE）搭复制；付款服务两个账号 × 三种 JDBC URL 的连接矩阵（Connector/J 9.7.0 和老的 5.1.49）
#   2  用 8.4 自带的 MySQL Shell 跑升级检查 util.checkForServerUpgrade
#   3  从库慢关机，换 8.4.13 镜像、同一个数据卷原地升级：日志、耗时、复制还通不通、老命令还能不能用
#   4  切换：8.4 从库升为主库，再跑连接矩阵；修法：临时打开 mysql_native_password、改账号插件、caching_sha2 的缓存
#   5  旧主库也原地升 8.4，挂回来当从库；8.0 和 8.4 的变量默认值对比
#   6  单向门：拿 8.0.46 去启动已经升过级的数据卷
# 用法：./run.sh | ./run.sh clean      本机不在 docker 组里：DOCKER="sudo docker" ./run.sh
set -uo pipefail
cd "$(dirname "$0")"
DOCKER=${DOCKER:-docker}
OLD=mysql:8.0.46 NEW=mysql:8.4.13
NET=up-net A=up-a B=up-b PA=33094 PB=33095
OUT=target/out; mkdir -p $OUT
clean() { $DOCKER rm -f $A $B up-probe >/dev/null 2>&1; $DOCKER volume rm $A-data $B-data >/dev/null 2>&1; $DOCKER network rm $NET >/dev/null 2>&1; true; }
[ "${1:-}" = clean ] && { clean; exit 0; }

MVN="mvn -q -f ../../pom.xml -pl database/mysql-84-upgrade"
$MVN compile || exit 1
CP=$($MVN dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime):target/classes
mvn -q dependency:copy -Dartifact=mysql:mysql-connector-java:5.1.49 -DoutputDirectory=target/old >/dev/null || exit 1
CP_OLD=target/old/mysql-connector-java-5.1.49.jar:target/classes
J="java -cp $CP labs.database.mysql_84_upgrade.ConnectMatrix"
J_OLD="java -cp $CP_OLD labs.database.mysql_84_upgrade.ConnectMatrix"

# 两台都开 GTID，buffer pool 给 256M；其余全用各自版本的默认值
COMMON="--gtid-mode=ON --enforce-gtid-consistency=ON --innodb-buffer-pool-size=256M"
start() { # 名字 镜像 端口 server-id [额外参数…]
  local n=$1 img=$2 port=$3 id=$4; shift 4
  $DOCKER run -d --name $n --hostname $n --network $NET -p 127.0.0.1:$port:3306 -v $n-data:/var/lib/mysql \
    -e MYSQL_ROOT_PASSWORD=root $img $COMMON --server-id=$id "$@" >/dev/null
}
ready() { local t0=$(date +%s.%N); until $DOCKER exec $1 mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e 'select 1' >/dev/null 2>&1; do
  sleep 1; [ "$($DOCKER inspect -f '{{.State.Running}}' $1)" = true ] || { echo "  !! $1 退出了"; return 1; }; done
  printf '  %s 可连接，用了 %.1f s\n' $1 "$(echo "$(date +%s.%N) - $t0" | bc)"; }
sql() { $DOCKER exec $1 mysql --protocol=TCP -h127.0.0.1 -uroot -proot -N -e "$2" 2>&1 | grep -v 'Using a password'; }
sqlv() { $DOCKER exec $1 mysql --protocol=TCP -h127.0.0.1 -uroot -proot -e "$2" 2>&1 | grep -v 'Using a password'; }
h() { echo; echo "### $*"; }

clean; $DOCKER network create $NET >/dev/null

h "1. 两台 $OLD"
start $A $OLD $PA 1; start $B $OLD $PB 2; ready $A; ready $B
sql $A "CREATE DATABASE shop;
  CREATE TABLE shop.orders (id BIGINT PRIMARY KEY, order_no VARCHAR(20) NOT NULL, user_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL, qty INT NOT NULL, amount_cents BIGINT NOT NULL, status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL, paid_at DATETIME(6) NULL, KEY idx_user(user_id)) ENGINE=InnoDB;
  SET SESSION cte_max_recursion_depth=100000;
  INSERT INTO shop.orders WITH RECURSIVE t(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM t WHERE i<100000)
    SELECT i, CONCAT('NO',i), i%5000, 88, 2, 17600, 'CREATED', NOW(6), NULL FROM t;
  -- 老库里常见的暗雷：外键指向一个非唯一索引（orders.user_id 不唯一）
  CREATE TABLE shop.refunds (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES shop.orders(user_id)) ENGINE=InnoDB;
  -- 付款服务的两个账号：老账号是当年在 my.cnf 里配了 mysql_native_password 时建的
  CREATE USER pay_native@'%' IDENTIFIED WITH mysql_native_password BY 'Pay#2026';
  CREATE USER pay_sha2@'%' IDENTIFIED BY 'Pay#2026';
  GRANT SELECT, UPDATE ON shop.* TO pay_native@'%', pay_sha2@'%';
  -- 列名 manual（是否人工发放），8.4 起 MANUAL 是保留字
  CREATE TABLE shop.coupons (id BIGINT PRIMARY KEY, manual TINYINT NOT NULL DEFAULT 0) ENGINE=InnoDB;
  CREATE USER repl@'%' IDENTIFIED BY 'repl'; GRANT REPLICATION SLAVE ON *.* TO repl@'%';"
echo "  主库 SHOW MASTER STATUS: $(sql $A 'SHOW MASTER STATUS' | cut -f1,2)"
echo "  从库跑老运维脚本 CHANGE MASTER TO … ; START SLAVE:"
sql $B "CHANGE MASTER TO MASTER_HOST='$A', MASTER_USER='repl', MASTER_PASSWORD='repl', MASTER_AUTO_POSITION=1, GET_MASTER_PUBLIC_KEY=1, MASTER_CONNECT_RETRY=2;
  START SLAVE; SHOW WARNINGS;" | sed 's/^/    /' | cut -c1-160
sleep 3; echo "  从库 SHOW SLAVE STATUS: $(sqlv $B 'SHOW SLAVE STATUS\G' | grep -E 'Slave_(IO|SQL)_Running:' | xargs)"
$J matrix $PA "主库 8.0.46" ; $J_OLD matrix $PA "主库 8.0.46" 2>/dev/null
sql $B "SHOW GLOBAL VARIABLES" > $OUT/vars-80.tsv

h "2. 升级检查：8.4 镜像里的 MySQL Shell 检查 8.0 主库"
# 镜像里的 Shell 是 8.4.10，targetVersion 写 8.4（写 8.4.13 它会说“不支持”）
$DOCKER run --rm --name up-probe --network $NET $NEW mysqlsh --uri root:root@$A:3306 -- util check-for-server-upgrade \
  --target-version=8.4 --output-format=TEXT > $OUT/checker.txt 2>&1
grep -E '^[0-9]+\)|^\(|Errors:|Warnings:|Notices:|^ +- |default value will change' $OUT/checker.txt | sed 's/^/    /' | cut -c1-160

h "3. 从库原地升级：慢关机 → 换 $NEW 镜像，同一个数据卷"
sql $B "SET GLOBAL innodb_fast_shutdown=0"
t0=$(date +%s.%N); $DOCKER stop -t 300 $B >/dev/null; printf '  慢关机用了 %.1f s\n' "$(echo "$(date +%s.%N) - $t0" | bc)"
$DOCKER rm $B >/dev/null; start $B $NEW $PB 2; ready $B
$DOCKER logs $B 2>&1 | grep -iE 'upgrad|ERROR|deprecat|removed' | sed 's/^/    /' | cut -c1-220 | head -25
sleep 2
echo "  新语法 SHOW REPLICA STATUS: $(sqlv $B 'SHOW REPLICA STATUS\G' | grep -E 'Replica_(IO|SQL)_Running:|Source_Host' | xargs)"
sql $A "UPDATE shop.orders SET status='PAID', paid_at=NOW(6) WHERE id=1"; sleep 2
echo "  8.0 主库付一笔，8.4 从库看到: $(sql $B "SELECT id,status FROM shop.orders WHERE id=1")"
for q in 'SHOW SLAVE STATUS' 'SHOW MASTER STATUS' 'STOP SLAVE' 'RESET MASTER'; do echo "  老命令 $q → $(sql $B "$q" | head -1 | cut -c1-140)"; done
echo "  新命令 SHOW BINARY LOG STATUS → $(sql $B 'SHOW BINARY LOG STATUS' | cut -f1,2)"
echo "  老 SQL：SELECT id, manual FROM coupons → $(sql $B "SELECT id, manual FROM shop.coupons" | head -1 | cut -c1-150)"
echo "  加反引号：SELECT id, \`manual\` FROM coupons → $(sql $B 'SELECT COUNT(`manual`) FROM shop.coupons' | head -1)"
echo "  restrict_fk_on_non_standard_key = $(sql $B 'SELECT @@restrict_fk_on_non_standard_key')"
echo "  在 8.4 上再建一个指向非唯一索引的外键 → $(sql $B "SET SESSION sql_log_bin=0; CREATE TABLE shop.refunds2 (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, FOREIGN KEY (user_id) REFERENCES shop.orders(user_id))" | cut -c1-160)"
echo "  已有的 refunds 表还在: $(sql $B "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE TABLE_NAME='refunds'") 个外键"
sql $B "SHOW GLOBAL VARIABLES" > $OUT/vars-84.tsv

h "4. 切换：8.4 从库升为主库"
sql $A "SET GLOBAL super_read_only=ON"
G=$(sql $A "SELECT @@gtid_executed" | tr -d '\n')
sql $B "SELECT WAIT_FOR_EXECUTED_GTID_SET('$G', 10); STOP REPLICA; RESET REPLICA ALL;" >/dev/null
echo "  新主库 $B 已追平 $G"
$J matrix $PB "新主库 8.4.13" ; $J_OLD matrix $PB "新主库 8.4.13" 2>/dev/null
$DOCKER logs $B 2>&1 | grep -i 'native' | tail -2 | sed 's/^/    服务端日志: /' | cut -c1-220
echo "  修法 A：ALTER USER pay_native IDENTIFIED WITH caching_sha2_password → $(sql $B "ALTER USER pay_native@'%' IDENTIFIED WITH caching_sha2_password BY 'Pay#2026'" | head -1)"
$J matrix $PB "改插件之后"
$J cache $PB
echo "  修法 B（临时）：带 --mysql-native-password=ON 重启，把老账号改回 native 再试"
sql $B "ALTER USER pay_native@'%' IDENTIFIED WITH mysql_native_password BY 'Pay#2026'" | head -1 | sed 's/^/    没打开时改回去: /'
$DOCKER stop -t 60 $B >/dev/null; $DOCKER rm $B >/dev/null; start $B $NEW $PB 2 --mysql-native-password=ON; ready $B
sql $B "ALTER USER pay_native@'%' IDENTIFIED WITH mysql_native_password BY 'Pay#2026'"
$J matrix $PB "8.4 + --mysql-native-password=ON"
$DOCKER logs $B 2>&1 | grep -iE 'native.*deprecat|deprecat.*native' | tail -1 | sed 's/^/    服务端日志: /' | cut -c1-220

h "5. 旧主库也升 8.4，挂到新主库下面当从库"
$DOCKER stop -t 300 $A >/dev/null; $DOCKER rm $A >/dev/null; start $A $NEW $PA 1; ready $A
sqlv $A "SET GLOBAL super_read_only=OFF; CHANGE REPLICATION SOURCE TO SOURCE_HOST='$B', SOURCE_USER='repl', SOURCE_PASSWORD='repl', SOURCE_AUTO_POSITION=1, GET_SOURCE_PUBLIC_KEY=1, SOURCE_CONNECT_RETRY=2; START REPLICA;"
sql $B "UPDATE shop.orders SET status='PAID', paid_at=NOW(6) WHERE id=2"; sleep 3
echo "  $(sqlv $A 'SHOW REPLICA STATUS\G' | grep -E 'Replica_(IO|SQL)_Running:' | xargs) · id=2: $(sql $A "SELECT status FROM shop.orders WHERE id=2")"
sqlv $A "SELECT LAST_ERROR_NUMBER, LAST_ERROR_MESSAGE FROM performance_schema.replication_applier_status_by_worker WHERE LAST_ERROR_NUMBER<>0 LIMIT 1\G" | grep -E 'LAST_ERROR' | sed 's/^ */    /' | cut -c1-260
echo "  从库也带 --mysql-native-password=ON 重启，START REPLICA"
$DOCKER stop -t 60 $A >/dev/null; $DOCKER rm $A >/dev/null; start $A $NEW $PA 1 --mysql-native-password=ON; ready $A
sql $A "START REPLICA"; sleep 3
echo "  $(sqlv $A 'SHOW REPLICA STATUS\G' | grep -E 'Replica_(IO|SQL)_Running:' | xargs) · id=2: $(sql $A "SELECT status FROM shop.orders WHERE id=2")"
python3 - $OUT/vars-80.tsv $OUT/vars-84.tsv > $OUT/vars-diff.txt <<'PY'
import sys
def load(p):
    d={}
    for line in open(p, encoding='utf-8', errors='replace'):
        k,_,v=line.rstrip('\n').partition('\t'); d[k]=v
    return d
a,b=load(sys.argv[1]),load(sys.argv[2])
skip=('version','server_uuid','pid_file','hostname','timestamp','gtid_','report_','datadir')
for k in sorted(set(a)|set(b)):
    if any(s in k for s in skip): continue
    va,vb=a.get(k,'（8.0 有，8.4 删了）' if k in a else '—'),b.get(k,'（8.4 新增）')
    if k not in b: vb='（8.4 删了）'
    if k not in a: va='（8.4 新增）'
    if va!=vb: print(f"{k}\t{va[:60]}\t{vb[:60]}")
PY
echo "  变量对比（同一台机器、同样的启动参数）: $(wc -l < $OUT/vars-diff.txt) 处不同，写在 $OUT/vars-diff.txt"
grep -E '^(innodb_(adaptive_hash_index|change_buffering|flush_method|io_capacity|io_capacity_max|log_buffer_size|doublewrite_files|doublewrite_pages|page_cleaners|purge_threads|read_io_threads|use_fdatasync|buffer_pool_instances|parallel_read_threads|numa_interleave)|temptable_max_ram|temptable_use_mmap|temptable_max_mmap|default_authentication_plugin|authentication_policy|mysql_native_password|restrict_fk_on_non_standard_key|replica_parallel_workers|binlog_transaction_dependency_tracking|expire_logs_days|master_info_repository)\b' $OUT/vars-diff.txt | column -t -s $'\t' | sed 's/^/    /'

h "6. 单向门：用 $OLD 启动已经升过级的数据卷"
$DOCKER stop -t 60 $A >/dev/null; $DOCKER rm $A >/dev/null; start $A $OLD $PA 1; sleep 15
echo "  容器状态: $($DOCKER inspect -f '{{.State.Status}} exit={{.State.ExitCode}}' $A)"
$DOCKER logs $A 2>&1 | grep -E '\[ERROR\]' | head -5 | sed 's/^/    /' | cut -c1-240
echo; echo "做完了。删容器和数据卷：./run.sh clean"
