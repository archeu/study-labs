# 88 号的锁有了两个主人：Redis 重启、切主以后丢了什么

对应页面：[88 号的锁有了两个主人：Redis 重启、切主以后丢了什么](https://archeu.github.io/study-notes/redis/redis-restart-failover.html)

交易靶场的商品缓存（`product:<id>`）和重建锁（`lock:product:<id>`），在 Redis 重启、主从切换、主库自动拉起时各丢了什么。

## 怎么跑

需要 Docker。`Lab` 自己用 docker 命令起停 `redis:8.10.3-alpine` 容器（主库 `rf-m` 63791、从库 `rf-r` 63792，网络 `rf-net`、`rf-rep`），跑完自动删。

```bash
./run.sh              # restart → failover → wipe，约 2 分钟
./run.sh failover     # 只跑一段：restart / failover / wipe
./run.sh clean        # 删残留的容器和网络
DOCKER="sudo docker" JAVA=/usr/lib/jvm/java-17-openjdk-arm64/bin/java ./run.sh   # 不在 docker 组里时；换 JDK
JAVA_OPTS="-Dn=50000" ./run.sh restart   # 商品数，默认 20 万
```

| 段 | 做什么 |
|---|---|
| `restart` | 不持久化 / RDB / AOF 纯命令 / AOF 混合 各写 20 万个商品，`docker stop` 再 `docker start`，裸 socket 探针每 2 ms 发一次 `GET product:88` |
| `failover` | 正常时 `WAIT 1` 多快；断开主从之间的网络后 A 在主库拿锁，kill 主库、提拔从库，B 再拿锁；再看 `min-replicas-to-write 1`、`max-lag 2` 多久开始拒写 |
| `wipe` | 主库不持久化，kill 后马上拉起，看从库多久被清空 |

主从复制单独走 `rf-rep` 网络，断网用 `docker network disconnect`。`docker pause` 模拟不了：进程冻住了，内核照样把主库发来的数据收进 socket 缓冲区，解冻后从库还是拿到了锁。

## 预期输出

Redis 8.10.3 容器，本机 2 核 ARM（aarch64），JDK 17 运行（代码按 JDK 8 写）。数字每遍略有不同：

```
[restart]
  RDB（默认 save）   停机 1770 ms；文件 132120 KB dump.rdb
  RDB（默认 save）   从 docker start 算：第一次有回复 251 ms，LOADING 126 次，第一次读到 88 号 666 ms
  AOF 纯命令         从 docker start 算：第一次有回复 228 ms，LOADING 220 次，第一次读到 88 号 1041 ms
  AOF 混合（默认）   从 docker start 算：第一次有回复 309 ms，LOADING 162 次，第一次读到 88 号 951 ms
[failover]
  正常：SET 后 WAIT 1 等到从库确认，p50 210～378 µs
  WAIT 1 500 → 0（548 ms 后返回）
  新主库上：GET product:88 → null；请求 B：SET lock:product:88 … NX PX 30000 → OK
  A 用 Lua 解锁（比对 token）→ 0（没误删 B 的锁）
  主从断网后 2952 ms 第一次被拒：NOREPLICAS Not enough good replicas to write.
[wipe]
  主库拉起后 5～6 s：从库 dbsize 0
```

`AOF 纯命令` 那一组绕开了镜像入口脚本、不加载模块：镜像自带的 RediSearch 要求 `aof-use-rdb-preamble yes`，否则进程直接退出。

## 练习

先猜结果，再运行。

1. 在 `failover` 段 A 拿锁之后加 `WAIT 1 100`，返回 0 就删锁、按“没拿到”处理。B 还会拿到第二把锁吗？A 那边会发生什么？
2. `restart` 段再加一个探针发 `PING`，另一个发 `INFO persistence` 读 `loading_loaded_perc`。PING 在加载期间回什么？
3. 把“AOF 混合”那一组的 `--save ""` 去掉，停机时间会变成多少？重启后从哪个文件加载？
