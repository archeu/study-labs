# 代码没改，行为变了：从 MySQL 换到 TiDB 要注意的四件事

对应页面：[代码没改，行为变了：从 MySQL 换到 TiDB 要注意的四件事](https://study.ainiya.de/database/mysql-to-tidb-pitfalls.html)

## 怎么跑

```bash
DOCKER="sudo docker" ./run.sh up all      # 起 TiDB 8.5.8 小集群（复用 ../distributed-db-tidb-oceanbase/cluster）和 MySQL 8.4（127.0.0.1:33115），跑全部
DOCKER="sudo docker" ./run.sh iso         # 已经起好了，只跑一段：autoinc | hotspot | bigtxn | iso
DOCKER="sudo docker" ./run.sh down
```

`PitfallsLab`：同样的 SQL 在 MySQL 8.4 和 TiDB 8.5.8 上各跑一遍（tidb2 的单事务上限显式设成 10 MiB）。

## 预期输出

2 核 ARM 虚拟机，JDK 17：

```
autoinc：两台 TiDB 交替插入 → 1,2,3（tidb1）30001,30002,30003（tidb2）；重启 tidb1 后 60001；AUTO_ID_CACHE = 1 → 1..6；MySQL → 1..6
hotspot：AUTO_INCREMENT 2 万行全在第 0 个 Region；AUTO_RANDOM 分到 8 个 Region（每个都是 500 的倍数：每 500 行一个事务）
bigtxn：30 MB 的 INSERT … SELECT：tidb2 → [8004] Transaction is too large；tidb1 → 成功；tidb2 + bulk → 成功；单行 7 MB → [8025] entry too large
iso：MySQL T2 插入被间隙锁挡 3 s 后 [1205]；TiDB T2 13 ms 成功，T1 普通读 2 行、FOR UPDATE 读 3 行
```

## 练习

1. 给 `t_iso` 加 `slot` 列和 `UNIQUE (slot)`，在 TiDB 上重跑 iso。
2. hotspot 改成每行一个事务插 2000 行，看 AUTO_RANDOM 的分布还是不是整百。
3. 写 `DELETE … LIMIT 5000` 的循环，在 tidb2 上把 `t_big` 删完。
4. `SET tidb_txn_mode = 'optimistic'` 再跑 iso。
