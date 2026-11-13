# orders 加一列，支付接口全卡住了：表结构设计与大表变更（Online DDL）

对应页面：[orders 加一列，支付接口全卡住了：表结构设计与大表变更（Online DDL）](https://archeu.github.io/study-notes/database/mysql-online-ddl.html)

`run.sh` 起一主一从两个 MySQL 8.4 容器（GTID 异步复制），每段实验前灌和全景 J06 同一份 100 万行 `orders`，然后跑 `DdlLab`：

- `algos`：后台每 20 ms 付一笔款（UPDATE 9527），同时依次做 INSTANT 加列、INPLACE 加列（重建）、INPLACE 加索引、VARCHAR 扩长（63 / 64 个字符）、INT→BIGINT（INSTANT / INPLACE 被拒，COPY）。每种看主库用时、期间付款最慢多久、主库做完后从库又过多久才放完。最后在小表上反复 INSTANT 加列删列，数到行版本上限。
- `mdl`：会话 A 开事务 SELECT 一行不提交，另一个线程 INSTANT 加列，再来 40 个用户请求（一半 UPDATE、一半 SELECT），看 `metadata_locks`、processlist、`sys.schema_table_lock_waits`，A 第 5 秒提交。然后用 `lock_wait_timeout=1` + 重试再来一遍。

## 怎么跑

需要 Docker（拉 `mysql:8.4`，约 800 MB）、JDK 8+、Maven。

```bash
cd java/database/mysql-online-ddl
./run.sh          # 起容器、配复制，跑 algos mdl（本机不在 docker 组里：DOCKER="sudo docker" ./run.sh）
./run.sh again    # 容器留着，重灌数据再跑
./run.sh clean    # 删容器、数据卷和网络
```

主库 127.0.0.1:33091，从库 33092，root / root。

## 预期输出

2 核 ARM，JDK 17.0.21，Connector/J 9.7.0，MySQL 8.4.13（Docker，buffer pool 512 MB），第二遍：

```
[algos] 后台每 20 ms 付一笔款（UPDATE 9527）；平时：中位 4.55 ms，最慢 17.4 ms
  INSTANT 加列             主库     30.9 ms | 期间付款   1 笔，最慢     30.9 ms | 主库做完后从库又过了      1.9 ms 才放完
  INPLACE 加列（重建表）   主库   7235.5 ms | 期间付款 257 笔，最慢     62.0 ms | 主库做完后从库又过了   5965.4 ms 才放完
  INPLACE 加索引           主库   3830.4 ms | 期间付款 135 笔，最慢     53.2 ms | 主库做完后从库又过了   2949.5 ms 才放完
  VARCHAR(16→63) INPLACE   主库     13.2 ms | 期间付款   0 笔，最慢      0.0 ms | 主库做完后从库又过了     22.7 ms 才放完
  VARCHAR(63→64) INPLACE   被拒：1846 ALGORITHM=INPLACE is not supported. Reason: Cannot change column type INPLACE. Try ALGORITHM=COPY.
  INT→BIGINT 要 INSTANT    被拒：1846 … Need to rebuild the table to change column type. Try ALGORITHM=COPY/INPLACE.
  INT→BIGINT 要 INPLACE    被拒：1846 … Cannot change column type INPLACE. Try ALGORITHM=COPY.
  INT→BIGINT 只能 COPY     主库  11023.0 ms | 期间付款   1 笔，最慢  11017.7 ms | 主库做完后从库又过了  12407.4 ms 才放完
  小表 t_ver 反复 INSTANT 加列/删列：成功 64 次，行版本 = 64
    下一次：4092 Maximum row versions reached for table trade/t_ver. ...
    不写 ALGORITHM 再加一次：成功，48.0 ms，行版本 = 0
[mdl] 会话 A：BEGIN; SELECT ... FROM orders WHERE id=1;（不提交）
    SHARED_READ GRANTED 1 / SHARED_UPGRADABLE GRANTED 1 / EXCLUSIVE PENDING 1 / SHARED_READ PENDING 20 / SHARED_WRITE PENDING 20
  processlist 里 'Waiting for table metadata lock' 的会话：41 个
  40 个用户请求：中位 4249 ms，最慢 4749 ms，超过 500 ms 的 40 个
[mdl] 修法：ALTER 前 SET SESSION lock_wait_timeout = 1，等不到锁就放弃，过 1 秒再试
  ALTER（INSTANT）从发出到完成 5.0 s（中途放弃 2 次）
  40 个用户请求：中位 210 ms，最慢 714 ms，超过 500 ms 的 9 个
```

第一遍：INSTANT 35 ms；重建 8.6 s / 从库再 6.7 s；建索引 4.3 s / 从库再 3.3 s；COPY 11.5 s / 从库再 11.5 s；元数据锁中位 4.29 s、最慢 4.76 s。

## 练习

先猜结果，再运行。

1. 把 `mdl` 里会话 A 换成自动提交的 `SELECT SLEEP(5) FROM orders LIMIT 1`，ALTER 和 40 个请求还会被堵吗？换成“BEGIN; SELECT; COMMIT; 连接空闲 5 秒”呢？
2. 主库 `SET GLOBAL innodb_online_alter_log_max_size = 65536`，后台改成每批 5000 行的批量更新，同时 INPLACE 加索引：DDL 成功吗？
3. 用 gh-ost 做 `MODIFY qty BIGINT NOT NULL`，同时跑付款和心跳：付款最慢多少、从库最大延迟多少、总耗时是 COPY 的几倍？
