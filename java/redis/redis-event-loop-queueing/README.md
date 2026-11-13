# 279 µs 里只有 2 µs 在 Redis：一条连接、一个线程上的排队

对应页面：[279 µs 里只有 2 µs 在 Redis：一条连接、一个线程上的排队](https://archeu.github.io/study-notes/redis/redis-event-loop-queueing.html)（J07 全景《一次商品详情查询》第 2、3 站）

## 怎么跑

需要 Docker、Maven、JDK 8 及以上。

```bash
./run.sh            # 起 Redis 8.10.3 容器，三段全跑（share → slow → block），约 1 分钟
./run.sh slow       # 容器留着时，只跑某一段：share / slow / block
./run.sh clean      # 删掉容器
DOCKER="sudo docker" ./run.sh   # 当前用户不在 docker 组里时
```

Linux 上 `run.sh` 直连容器 IP（绕开 docker-proxy 的用户态转发）；macOS 上走 `127.0.0.1:63799` 端口映射。

IDE 里运行：先 `./run.sh` 起容器，再运行 `labs.redis.redis_event_loop_queueing.Lab`，VM 参数 `-Dhost=… -Dport=…`，程序参数写段名。

| 段 | 看什么 |
|---|---|
| `share` | 一次 GET 在不同的路上要多久：自己拼 RESP 字节的裸 socket、Lettuce 单线程、4 条连接 × 50 线程、1 条连接 × 200 线程、200 条连接；每次读 `INFO commandstats` 和 `INFO cpu`，算 Redis 执行命令占了多少时间、主线程用了多少 CPU |
| `slow` | 灌 100 万个 key，200 个线程查 88 号时，运维跑一条 `KEYS session:*`：它前后的 GET 等多久，SLOWLOG、commandstats、latencystats 各显示什么；再换成 `SCAN COUNT 1000` |
| `block` | 1 条共享连接、50 个线程查 88 号时，在同一条连接上 / 另开一条连接上：`BLPOP` 2 秒、`GET` 16 MB 大 value；再把命令超时改成 500 ms，看超时次数和之后有没有错位 |

## 预期输出

本机 2 核 ARM，JDK 17.0.21（代码按 `--release 8` 编译），Redis 8.10.3（`redis:8.10.3-alpine` 容器，直连容器 IP），Lettuce 6.8.2。吞吐受客户端和 Redis 抢 CPU 影响很大，只看比例。

```
[1] 裸 socket，单线程顺序 GET 20000 次        p50 30 µs  p99 62 µs
[2] Lettuce，1 条连接 × 1 个线程，GET 20000 次  p50 101 µs  p99 399 µs
[3] 4 条连接 × 50 个线程，不停地查          p50 8.09 ms  p99 24 ms，21,993 次/秒（另一次 2.87 ms，52,728 次/秒）
[4] 1 条连接 × 200 个线程，不停地查         p50 3.76 ms  p99 8.75 ms，50,546 次/秒
      Redis：GET 执行 0.32 µs/次（commandstats），执行命令合计 1.6% 的时间；主线程 CPU 46%
[5] 200 条连接 × 1 个线程，不停地查         p50 4.21 ms  p99 14 ms，40,438 次/秒

[KEYS] 运维这一步用了 115 ms
  在它前后 5 ms 内发出的 GET（791 个） p50 3.54 ms  p99 114 ms  max 116 ms
  Redis 眼里的 GET：commandstats usec_per_call=0.30；latencystats p50=0.001,p99=2.007,p99.9=2.007
  SLOWLOG 里有 1 条：105842 µs  [KEYS, session:*]
[SCAN] 运维这一步用了 2250 ms（SCAN 调了 1000 次）
  在它前后 5 ms 内发出的 GET（112347 个） p50 2.86 ms  p99 8.95 ms
  SLOWLOG 里有 0 条

[BLPOP 2 秒，和大家共用一条连接]   这期间发出的 GET（52 个）     p50 2026 ms
[BLPOP 2 秒，自己开一条连接]       这期间发出的 GET（79233 个）  p99 3.28 ms
[GET 16 MB 大 value，共用一条连接]  这期间发出的 GET（51 个）     p50 38 ms
[GET 16 MB 大 value，自己开一条连接] 这期间发出的 GET（1014 个）  max 4.99 ms
[超时 500 ms + 共享连接上 BLPOP 2 秒]
  BLPOP 自己：客户端超时（510 ms）
  GET 超时次数 157，最后一次超时发生在 BLPOP 开始后 2010 ms
  之后再 GET 88 号：拿到的是 88 号的详情，没有错位
```

走端口映射（`-Dhost=127.0.0.1 -Dport=63799`）时，裸 socket 的来回是 p50 62 µs、p99 104 µs。

## 练习

先猜结果，再运行。

1. **打开 IO 线程**：`run.sh` 里的 `docker run … redis:8.10.3-alpine` 后面加上 `redis-server --io-threads 2`，跑 `./run.sh share`。主线程 CPU 会降吗？吞吐会涨吗？这台机器只有 2 个核，客户端也要用。
2. **SCAN 一次扫 10 万**：把 `slow` 段 SCAN 的 `limit(1000)` 改成 `limit(100000)`。SCAN 调几次？每次大约多久？这期间 GET 最慢多久？
3. **大 value 换大小**：把 `block` 段的 16 MB 改成 1 MB 和 64 MB。共用连接时，A 的 GET 各等多久？和大小成正比吗？独立连接时，别人还是只慢几毫秒吗？
