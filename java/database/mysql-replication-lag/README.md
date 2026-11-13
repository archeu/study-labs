# 付完款跳回订单页，还是待付款：主从复制、读写分离与复制延迟

对应页面：[付完款跳回订单页，还是待付款：主从复制、读写分离与复制延迟](https://archeu.github.io/study-notes/database/mysql-replication-lag.html)

交易靶场的 `orders` 表（100 万行，和全景 J06 同一份数据），一主一从，GTID 异步复制：

- `ReplLab setup`：主库建表、灌 100 万行，等从库追上
- `ReplLab stale`：付款后隔 0 / 5 / 20 / 50 ms 读从库，各 200 次，数读到 CREATED 的次数；修法一写后读主库，修法二用 `session_track_gtids=OWN_GTID` 拿到自己那笔的 GTID，在从库上 `WAIT_FOR_EXECUTED_GTID_SET` 再读
- `ReplLab bigtx`：30 万行一个事务 vs 拆成 60 批 × 5000 行；心跳表（主库每 100 ms 写 `NOW(6)`）量真实延迟，对照 `Seconds_Behind_Source`，打印时间序列
- `ReplLab break`：在从库上删一行，主库再改它：复制停在 1032、errant GTID；补回后打开 `super_read_only`，root 也写不进去，回放照常

## 怎么跑

需要 Docker（会拉 `mysql:8.4`，约 800 MB）。按 JDK 8 写法（`--release 8`），用 JDK 17 跑过。

```bash
cd java/database/mysql-replication-lag
DOCKER="sudo docker" ./run.sh          # 起两个容器、配复制、灌 100 万行 → stale bigtx break
DOCKER="sudo docker" ./run.sh again    # 容器留着，只重跑实验
DOCKER="sudo docker" ./run.sh clean    # 删容器（连同数据卷）和网络
```

## 预期输出

本机 2 核 ARM，JDK 17.0.21，Connector/J 9.7.0，Docker 里两个 MySQL 8.4.13（同一台机器，buffer pool 512 MB，GTID 打开，其余默认）。第二遍的输出（时间序列省略；errant GTID 那一行的 `:1-2` 是因为跑了两遍）：

```
[stale] 主库付款（自动提交），隔一会儿去从库读 9527；每种间隔 200 次
  隔  0 ms 读从库：199 / 200 次读到 CREATED（100%）
  隔  5 ms 读从库：  2 / 200 次读到 CREATED（1%）
  隔 20 ms 读从库：  0 / 200 次读到 CREATED（0%）
  隔 50 ms 读从库：  0 / 200 次读到 CREATED（0%）
  commit 返回到从库读到 PAID：中位 3.9 ms，p99 9.0 ms，最长 11.0 ms
[stale] 修法一 写后读主库：0 次读到 CREATED，读一次中位 0.48 ms
[stale] 修法二 等自己的 GTID（例如 10c1bce0-df14-11f1-bc62-0242ac160002:5420）：0 次读到 CREATED，0 次超时；等 + 读 中位 4.1 ms，p99 11.5 ms
[bigtx] 30 万行一个事务：主库用时 2.1 s（第 3.4 s 提交完）；从库第 7.3 s 追上 | 心跳延迟最大 4.1 s | Seconds_Behind_Source 最大 5
[bigtx] 拆成 60 批 × 5000 行：主库用时 4.4 s（第 5.7 s 提交完）；从库第 6.0 s 追上 | 心跳延迟最大 0.9 s | Seconds_Behind_Source 最大 1
[break] 从库 super_read_only=0，root 在从库上 DELETE id=9530
  主库改 9530 之后：IO=Yes SQL=No Seconds_Behind_Source=null
  Last_SQL_Errno=1032
  主库接着改 9540，从库上 9540 = CREATED（主库 = PAID）
  从库 gtid_executed 多出一个自己的 UUID（errant 事务）：10c1bce0-df14-11f1-bc62-0242ac160002:1-6014, 10dc7038-df14-11f1-bd98-0242ac160003:1-2
  补回 9530 再 START REPLICA SQL_THREAD：追上了，从库 9540 = PAID
  super_read_only=ON 后 root 再删：1290 The MySQL server is running with the --super-read-only option so it cannot execute this statement
  主库的改动照常回放：从库 9530 = CREATED
```

主从在同一台机器上，网络几乎不花时间，又在抢同样的 2 个核：轮询从库量出来的“可见时间”会偏大，回放也比独占机器时慢。

## 练习

先猜结果，再运行。

1. 从库 `replica_parallel_workers` 改成 1，再改回 4：8 个线程在主库上各改不同的订单（自动提交，各 2000 次），同时跑心跳，两次最大延迟差多少？
2. 大事务提交后 0.5 s 做一次付款，按“写后 1 s 内读主库”路由，第 1.2 s 读从库，读到什么？换成等 GTID（超时 1 s）呢？
3. 建一张没有主键的 `order_log`，灌 10 万行，主库一次 DELETE 1 万行，量心跳延迟；加上主键再跑一次。
