# 订单表一行没加，PG 里却胖了一倍：PostgreSQL 的多版本、VACUUM、索引类型和 JSONB

对应页面：[订单表一行没加，PG 里却胖了一倍：PostgreSQL 的多版本、VACUUM、索引类型和 JSONB](https://archeu.github.io/study-notes/database/pg-mvcc-vacuum-jsonb.html)

交易靶场的 `orders` 表（100 万行，和 MySQL 那几页同一份数据，多一列 `ext jsonb`），导进 PostgreSQL 18.7。看一条付款 UPDATE 在堆页里留下什么、VACUUM 清什么不清什么，以及 PG 的索引类型和 JSONB。表上关了 autovacuum（`autovacuum_enabled = off`），VACUUM 都是手动发的，这样数字可复现。

- `PgLab setup`：建表、灌 100 万行、建 `user_id` 索引，装 `pageinspect` 和 `pgstattuple`
- `PgLab see`：⑤ 付款 UPDATE：两个连接各读到什么，`heap_page_items` 看新旧两个元组，提交后读一次触发页内修剪，VACUUM
- `PgLab bloat`：整表 UPDATE：表和索引翻倍；VACUUM 不缩；再改一遍复用空位；VACUUM FULL 缩回去
- `PgLab longtx`：REPEATABLE READ 报表事务开着，9527 被改 2 万次：VACUUM 删不掉、谁读得慢（EXPLAIN BUFFERS）、提交后再清
- `PgLab hot`：fillfactor 100 对 90，各 10 万行，改 1 万行 status / user_id：HOT 次数、表和索引大小
- `PgLab index`：JSONB 的 GIN（jsonb_ops / jsonb_path_ops）、`->>` 用不上 GIN、表达式索引、B-tree 对 BRIN、部分索引

## 怎么跑

需要 Docker（会拉 `postgres:18.7-alpine`，约 110 MB）。按 JDK 8 写法（`--release 8`），用 JDK 17 跑过。整套约 2 分钟。

```bash
cd java/database/pg-mvcc-vacuum-jsonb
DOCKER="sudo docker" ./run.sh         # 起容器 → 灌 100 万行 → see bloat longtx hot index
DOCKER="sudo docker" ./run.sh again   # 容器留着，重跑实验（index 段会把 99% 的订单改成 PAID，先 clean 再跑更干净）
DOCKER="sudo docker" ./run.sh clean   # 删容器（连同数据卷）
```

`pageinspect` 按 id 数版本时把 9527 写成小端字节（`\x3725000000000000`），x86 和 ARM 都适用。

## 预期输出

本机 2 核 ARM，JDK 17.0.21，pgJDBC 42.7.14，Docker 里 PostgreSQL 18.7（`shared_buffers=512MB`，其余默认）：

```
[setup] 100 万行，9.0 s | 表 159.4 MB，主键索引 21.4 MB，user_id 索引 11.1 MB
[see] 改之前      ctid=(194,21) xmin=756 xmax=0 status=CREATED
[see] 付款事务 xid=759，UPDATE 后、未提交：
      付款连接读到   ctid=(20408,9) xmin=759 xmax=0 status=PAID
      另一个连接读到 ctid=(194,21) xmin=756 xmax=759 status=CREATED
      两个版本都在表里（lp_flags 0=空闲 1=正常 2=重定向 3=死；get_raw_page 直接读页，不会触发清理）：
        (194,21) lp_flags=1 t_xmin=756 t_xmax=759 t_ctid=(20408,9)
        (20408,9) lp_flags=1 t_xmin=759 t_xmax=0 t_ctid=(20408,9)
      新版本那一页有 9 个元组；表一共 20409 页（原来的页都满了，新版本只能去表尾）
[see] COMMIT 之后，还没有人读过页 194：
        (194,21) lp_flags=1 t_xmin=756 t_xmax=759 t_ctid=(20408,9)
[see] 另一个连接读一次 9527：ctid=(20408,9) xmin=759 xmax=0 status=PAID，再看旧槽位：
        (194,21) lp_flags=3 t_xmin=null t_xmax=null t_ctid=null
      pgstattuple：live=1000000 dead=0 free_pct=1.1
[see] VACUUM：pages: 0 removed, 20409 remain, 2 scanned (0.01% of total), 0 eagerly scanned | tuples: 0 removed, 1000000 remain, 0 are dead but not yet removable | removable cutoff: 760, which was 0 XIDs old when operation ended | index scan bypassed: 1 pages from table (0.00% of total) have 1 dea…
        (194,21) lp_flags=3 t_xmin=null t_xmax=null t_ctid=null
[bloat] 起点        表 159.4 MB，主键索引 21.4 MB，user_id 索引 11.1 MB
[bloat] 整表 UPDATE 1000000 行 14.8 s → 表 318.9 MB，主键索引 42.9 MB，user_id 索引 22.3 MB | live=1000000 dead=1000000 free_pct=1.1
[bloat] VACUUM 1.2 s → 表 318.9 MB，主键索引 42.9 MB，user_id 索引 22.3 MB | live=1000000 dead=0 free_pct=50.4
        pages: 0 removed, 40817 remain, 40817 scanned (100.00% of total), 0 eagerly scanned | tuples: 1000000 removed, 1000000 remain, 0 are dead but not yet removable | removable cutoff: 762, which was 10 XIDs old when operation ended | index scan needed: 20409 pages from table (50.00% of total)…
[bloat] 再整表 UPDATE 一遍 11.1 s → 表 318.9 MB，主键索引 42.9 MB，user_id 索引 22.3 MB | live=1000000 dead=1000000 free_pct=1.1
[bloat] VACUUM FULL 3.8 s → 表 159.4 MB，主键索引 21.4 MB，user_id 索引 11.1 MB
[longtx] 报表事务开始（REPEATABLE READ），读到 qty=5
[longtx] 改之前新连接读 9527：0.588 ms，Index Scan using orders_pkey on orders  (actual rows=1.00 loops=1) / Buffers: shared hit=4
[longtx] 9527 被改了 20000 次（每次自动提交），24.7 s
[longtx] 报表事务读 9527：qty=5，1.109 ms，Index Scan using orders_pkey on orders  (actual rows=1.00 loops=1) / Buffers: shared hit=412
[longtx] 新连接读 9527：qty=20005，0.887 ms，Index Scan using orders_pkey on orders  (actual rows=1.00 loops=1) / Buffers: shared hit=412
[longtx] 新连接读隔壁 9528：0.253 ms
          表里 9527 的版本数（pageinspect 按 id 数）：20001
          报表事务的 backend_xmin 落后当前 xid：20000
[longtx] 报表事务开着时 VACUUM：pages: 0 removed, 20817 remain, 409 scanned (1.96% of total), 0 eagerly scanned | tuples: 0 removed, 1019959 remain, 20000 are dead but not yet removable | removable cutoff: 775, which was 20000 XIDs old when operation ended | index scan not needed: 0 pages from table (0.0…
          VACUUM 后新连接读 9527：0.871 ms，Index Scan using orders_pkey on orders  (actual rows=1.00 loops=1) / Buffers: shared hit=412
[longtx] 报表事务提交后 VACUUM：pages: 0 removed, 20817 remain, 409 scanned (1.96% of total), 0 eagerly scanned | tuples: 20000 removed, 999959 remain, 0 are dead but not yet removable | removable cutoff: 20775, which was 0 XIDs old when operation ended | index scan bypassed: 408 pages from table (1.96% …
          新连接读 9527：0.344 ms，Index Scan using orders_pkey on orders  (actual rows=1.00 loops=1) / Buffers: shared hit=4 | 9527 的版本数：1 | 表 162.6 MB，主键索引 21.5 MB，user_id 索引 11.1 MB
[hot] fillfactor 100：改 status 1 万行 → n_tup_upd=10000 n_tup_hot_upd=0 | 表 16328 KB→18280 KB | 主键索引 2208 KB→2208 KB | user_id 索引 2360 KB→2360 KB
[hot] fillfactor 100：再改 user_id 1 万行（索引列）→ 累计 n_tup_upd=10000 n_tup_hot_upd=0 | user_id 索引 → 2592 KB
[hot] fillfactor  90：改 status 1 万行 → n_tup_upd=10000 n_tup_hot_upd=9092 | 表 18184 KB→18384 KB | 主键索引 2208 KB→2208 KB | user_id 索引 2360 KB→2360 KB
[hot] fillfactor  90：再改 user_id 1 万行（索引列）→ 累计 n_tup_upd=20000 n_tup_hot_upd=9092 | user_id 索引 → 2360 KB
[index] ext 长这样：{"tags": [], "coupon": {"code": "C14"}, "channel": "app"}
[index] 没有索引：@> 165.3 ms，Buffers: shared hit=6905 read=13504 / Buffers: shared hit=6905 read=13504 / Buffers: shared hit=6905 read=13504 / Parallel Seq Scan on orders  (actual rows=343.67 loops=3) / Rows Removed by Filter: 332990 / Buffers: shared hit=6905 read=13504
[index] GIN jsonb_ops 建索引 0.8 s，4.3 MB
[index] GIN jsonb_path_ops 建索引 0.4 s，2.0 MB
[index] 有 GIN：@> 2.17 ms，Buffers: shared hit=1034 / Bitmap Heap Scan on orders  (actual rows=1031.00 loops=1) / Heap Blocks: exact=1031 / Buffers: shared hit=1034 / Bitmap Index Scan on gin_path  (actual rows=1031.00 loops=1) / Buffers: shared hit=3 / Buffers: shared hit=2
[index] 有 GIN：->> = 111.6 ms，Buffers: shared hit=13200 read=7209 / Buffers: shared hit=13200 read=7209 / Buffers: shared hit=13200 read=7209 / Parallel Seq Scan on orders  (actual rows=343.67 loops=3) / Rows Removed by Filter: 332990 / Buffers: shared hit=13200 read=7209
[index] 有 GIN：? 'coupon'（10% 的行）59.2 ms，Buffers: shared hit=20428 / Bitmap Heap Scan on orders  (actual rows=100000.00 loops=1) / Heap Blocks: exact=20409 / Buffers: shared hit=20428 / Bitmap Index Scan on gin_ops  (actual rows=100000.00 loops=1) / Buffers: shared hit=19 / Buffers: shared hit=1
[index] 表达式索引 6.6 MB 之后：->> = 1.71 ms，Buffers: shared hit=1035 / Bitmap Heap Scan on orders  (actual rows=1031.00 loops=1) / Heap Blocks: exact=1031 / Buffers: shared hit=1035 / Bitmap Index Scan on idx_coupon  (actual rows=1031.00 loops=1) / Buffers: shared hit=4
[index] 没有索引：一小时内的订单 67.0 ms，Buffers: shared hit=20409 / Buffers: shared hit=20409 / Buffers: shared hit=20409 / Parallel Seq Scan on orders  (actual rows=1200.00 loops=3) / Rows Removed by Filter: 332133 / Buffers: shared hit=20409
[index] B-tree(created_at) 21.4 MB：0.86 ms，Buffers: shared hit=14 / Index Only Scan using bt_created on orders  (actual rows=3600.00 loops=1) / Buffers: shared hit=14
[index] BRIN(created_at) 24 KB：2.59 ms，Buffers: shared hit=315 / Bitmap Heap Scan on orders  (actual rows=3600.00 loops=1) / Rows Removed by Index Recheck: 11696 / Heap Blocks: lossy=313 / Buffers: shared hit=315 / Bitmap Index Scan on brin_created  (actual rows=3130.00 loops=1) / Buffers: shared…
[index] 部分索引 WHERE status='CREATED'（1% 的行）136 KB，对比全量 user_id 索引 11.1 MB：0.78 ms，Index Scan using idx_unpaid on orders  (actual rows=5.00 loops=1) / Buffers: shared hit=7
```

hot 段的 `n_tup_upd, n_tup_hot_upd` 取自 `pg_stat_xact_user_tables`：统计每秒最多刷新一次，没刷出去的计数会累计到下一个事务里，所以 fillfactor 90 第二行显示 20000 / 9092，意思是改 user_id 的 1 万次里 HOT 0 次。

## 练习

先猜结果，再运行。

1. 在 `longtx()` 里把报表事务的隔离级别改成 `TRANSACTION_READ_COMMITTED`。猜：VACUUM 还会报 `20000 are dead but not yet removable` 吗？`age(backend_xmin)` 是多少？
2. `setup()` 建表时加 `fillfactor = 90`，重跑全部。猜：没有报表事务时，9527 改 2 万次，表还会多出 409 页吗？
3. 去掉 `autovacuum_enabled = off`，整表 UPDATE 一次，每 5 秒查一次 `n_dead_tup` 和 `last_autovacuum`。猜：多久以后会被清？（`autovacuum_naptime` 默认 60 s）
