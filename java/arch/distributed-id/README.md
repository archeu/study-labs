# 订单号 9527 从哪来：分布式 ID——号段与雪花算法

对应页面：[订单号 9527 从哪来：分布式 ID——号段与雪花算法](https://archeu.github.io/study-notes/arch/distributed-id.html)

交易靶场 J09 全景“一次下单跨三个库”的第 2 站：订单服务不访问任何库，从内存里的号段 [9001, 10000] 发出订单号 9527。
MySQL 8.4 跑在 Docker 里；雪花部分直接用 MyBatis-Plus 3.5.18 的 `Sequence`（只覆盖 `timeGen()` 来拨钟）。按 JDK 8 写法（`--release 8`）。

- `SegmentIdGen`：号段发号器。`UPDATE id_alloc SET max_id = max_id + step` 和 `SELECT` 在一个事务里；`doubleBuffer = true` 时当前段用掉 10% 就在后台预取下一段（美团 Leaf 的思路）。
- `SeataStyleIdWorker`：照 Seata 2.6.0 `IdWorker` 写的“改良雪花”，时间戳和序列号合成一个 `AtomicLong`，启动后不再看钟。
- `IdLab` 三段：
  - `segment`：两个实例各领一段、9527 是怎么来的、kill -9 重启丢多少号、两实例 × 4 线程 40 万个号查重、领号段时库慢 200 ms（单 buffer / 双 buffer）、库挂了还能撑多久
  - `snowflake`：拆开一个 ID、低流量时 `id % 4` 分库、单线程吞吐、时钟回拨 3 ms / 10 ms / 1 s、重启时钟回拨、两个实例同一个 workerId、Seata 变种遇到回拨
  - `btree`：往 InnoDB 插 30 万行，比 BIGINT 雪花（单实例 / 8 实例轮流）、随机 UUID v4、UUID v7（毫秒内随机 / 毫秒内递增）的页分裂和表大小

## 怎么跑

```bash
cd java/arch/distributed-id
./run.sh new all          # 起 MySQL 容器（127.0.0.1:33097），跑三段；参数二可以是 segment | snowflake | btree | all
./run.sh again snowflake  # 容器留着，只重跑
./run.sh clean            # 删容器
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh new all
```

## 预期输出

2 核 ARM · JDK 17.0（`--release 8` 编译）· MySQL 8.4.13 · MyBatis-Plus 3.5.18：

```
== segment ①：两个订单服务实例各领一段（id_alloc 里 order 的 max_id 从 9000 开始，step 1000）
A 启动领到 [9001, 10000]，B 启动领到 [10001, 11000]
A 发到第 527 个号：订单号 9527（全程没访问库，A 的 id_alloc 访问次数 = 1 + 预取 1）
接下来按时间先后交替下 6 单：A 9528 → B 10001 → A 9529 → B 10002 → A 9530 → B 10003
A 被 kill -9：当前段发到 9530，剩下 470 个，预取好的下一段 [11001, 12000] 也一起丢了
A 重启领到 [12001, 13000]：永远不会再发出来的号 1470 个（订单号有空洞，但没有重复）

== segment ②：两个实例 × 4 个线程，各发 50,000 个号（双 buffer），查重
发了 400,000 个号，用时 1.93 s（207,046 个/秒），重复 0 个，访问 id_alloc 402 次（一次领 1000 个）

== segment ③：领号段时库慢了 200 ms（DO SLEEP），按固定速率发 3 秒，看每次 nextId() 的耗时
写法            | 速率      | 一段能用多久 | 发了多少个 | 访问库 | 最慢一次 | 超过 50 ms 的调用
单 buffer       | 4,000/s |    250 ms   |   12,001  |   12 次 |    211 ms | 12
双 buffer       | 4,000/s |    250 ms   |   12,001  |   12 次 |      0 ms | 0
双 buffer       | 8,000/s |    125 ms   |   15,001  |   15 次 |    209 ms | 15

== segment ④：双 buffer，速率 4000 个/秒，发到第 200 个时 id_alloc 所在的库挂了（用开关模拟）
挂之前：当前段 [1, 1000]，预取好的下一段 [1001, 2000]
库挂了之后又发了 1800 个号，撑了 430 ms，然后：A：两段都发完了，库还没给新号段（这期间访问库失败 889 次）

== snowflake ①：拆开一个 MyBatis-Plus 雪花 ID（Sequence(workerId=1, datacenterId=1)）
id = 2121816155688669185（19 位十进制）
二进制 0 | 00111010111001000110100100011010011111010 | 00001 | 00001 | 000000000001
拆开：时间 2026-11-15T04:02:47.611Z | 机房 1 | 机器 1 | 序列 1
转成 JS 的 Number（double）再转回来：2121816155688669184，差了 1
不给 workerId，让 MyBatis-Plus 自己算（本机）：机房 1 | 机器 9 | 序列 1

== snowflake ②：低流量（每毫秒最多 1 个）发 1000 个号，按 id % 4 和 id % 3 分库
id % 4 → 库 0..3 各落了 [0, 529, 471, 0]；id % 3 → 库 0..2 各落了 [328, 322, 350]

== snowflake ③：单线程一口气发 3,000,000 个
用时 0.73 s，4,100,831 个/秒；跨了 732 毫秒（上限每毫秒 4096 个）

== snowflake ④：时钟回拨（MyBatis-Plus 3.5.18 真实的 Sequence.nextId()）
回拨 3 ms → 成功，这一次调用花了 6 ms（sleep 了回拨量 × 2）
回拨 10 ms → 抛异常：Clock moved backwards.  Refusing to generate id for 10 milliseconds
回拨 1000 ms → 抛异常：Clock moved backwards.  Refusing to generate id for 1000 milliseconds
  之后每 1 ms 调一次：失败 908 次，1005 ms 后才恢复（等墙上时钟走回原来的位置）

== snowflake ⑤：实例运行 300 ms 后重启，重启时钟被 NTP 往回拨了 300 ms（lastTimestamp 没存下来，查不出回拨）
重启前发了 2,810 个；重启后 300 ms 发了 2,810 个，其中 2,495 个和重启前的重复

== snowflake ⑥：两个实例配成了同一个 workerId / datacenterId（1, 1）
低流量：各自每 1 ms 发 1 个，2 秒：A 发 1,873 个，B 发 1,876 个，撞号 900 个
高流量：各自一口气发 500,000 个：A 发 500,000 个，B 发 500,000 个，撞号 435,020 个

== snowflake ⑦：照 Seata 2.6.0 IdWorker 写的变种（启动后不再看钟）
先在 worker 2 发：18859322099458049，5 ms 后在 worker 1 发：9852122844717057 → 后发的反而小（workerId 在高位）
回拨 1000 ms 后连续发 1.5 s：发了 2,082,701 个，没有异常，没有重复，严格递增 = true；其中 203 次调用 sleep 了 5 ms

== btree：往 InnoDB 插 300,000 行（主键 + 100 字节 payload，每批 1000 行一个事务）
主键                          | 用时    | 页分裂（index_page_splits） | 表大小（DATA_LENGTH）
BIGINT 雪花（递增）              |   5.0 s |    2,524                    |  39.6 MB（2,533 页）
BIGINT 雪花 8 个实例轮流写         |   5.3 s |    4,008                    |  71.6 MB（4,583 页）
CHAR(36) 随机 UUID v4        |   8.5 s |    4,420                    |  79.8 MB（5,105 页）
CHAR(36) UUID v7 毫秒内随机     |   6.9 s |    4,552                    |  81.9 MB（5,240 页）
CHAR(36) UUID v7 毫秒内递增     |   5.9 s |    3,102                    |  48.7 MB（3,115 页）
```

雪花 ID 的数值、MyBatis-Plus 自己算出来的“机器”号（和进程号有关）每次都不一样；吞吐、耗时随机器变。

## 练习

先猜结果，再运行。

1. 把 `segment ③` 里双 buffer 的预取阈值从“用掉 10%”改成“用掉 90%”（`s.idle() < 0.1 * step`），4000 个/秒时还会卡吗？要让 8000 个/秒也不卡，step 至少要多大？
2. 给 `SegmentIdGen` 加 Leaf 的动态步长：这一段从领到发完不到 15 分钟，下次 step 翻倍（上限 1,000,000）。再跑 `segment ④`，库挂了之后能撑多久？
3. 在 `snowflake ⑤` 里，启动时先读一个文件里记下的“上次发号时间”，比现在还晚就拒绝启动（Leaf 用 ZooKeeper 节点做同一件事，每 3 秒上报一次）。重复还剩多少？为什么不是 0？
4. `btree` 里把“8 个实例轮流写”换成 2 个实例，页数会落在哪？再把写入速度降到每毫秒 1 行（先生成好 ID 再插）呢？
