# 不自己拆，换个数据库：TiDB、OceanBase、GoldenDB 替你做了什么

对应页面：[不自己拆，换个数据库：TiDB、OceanBase、GoldenDB 替你做了什么](https://archeu.github.io/study-notes/database/distributed-db-tidb-oceanbase.html)

## 怎么跑

```bash
DOCKER="sudo docker" ./run.sh up all     # cluster/cluster.sh up：1 PD + 3 TiKV + 2 TiDB（v8.5.8，127.0.0.1:4001 / 4002），再跑 TidbLab
DOCKER="sudo docker" ./run.sh tour       # 集群已起，只跑一段：tour | failover | failover-leader
DOCKER="sudo docker" ./run.sh down       # 删集群
```

- `cluster/tikv.toml`：每个 TiKV 块缓存 256 MB（2 核小机器上跑 3 个）。`cluster/tidb-small-txn.toml`：tidb2 的单事务上限 10 MiB，给下一页的大事务实验用。
- `TidbLab tour`：TIKV_STORE_STATUS、表的 Region / leader / 副本、SPLIT TABLE、EXPLAIN ANALYZE 看 cop[tikv] 下推、TSO。
- `TidbLab failover` / `failover-leader`：每 20 ms 写一行，第 5 秒 `docker kill` 一台只有 follower 的 TiKV / 持有 leader 的 TiKV，按秒统计成功、失败、最慢一次。

## 预期输出

2 核 ARM 虚拟机，JDK 17：

```
tour：3 个 store；2 万行 1 个 Region，SPLIT 后 4 个，副本都在 store 1、2、5；COUNT 下推 cop_task num: 4
failover（杀 follower 所在的 TiKV）：1638 次写入，0 失败，最慢 42 ms
failover-leader（杀 leader 所在的 TiKV）：一次写入卡 13905 ms，0 失败，之后恢复每秒约 40 次
```

## 练习

1. 同时杀两台 TiKV，看写和读；再启动一台，多久恢复写入。
2. 加一台 tikv4，看 Region 和 leader 怎么均衡过去。
3. `socketTimeout` 改成 5000 重跑 failover-leader，查表看“失败”的那几行到底写进去没有。
4. 灌 200 万行，看 Region 什么时候自己分裂。
