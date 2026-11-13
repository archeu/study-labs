# 全景：一次商品详情查询

对应页面：[全景：一次商品详情查询](https://archeu.github.io/study-notes/java/pano-j07-product-detail-cache.html)（Java 地图 J07 · Redis 与缓存设计）

交易靶场的商品服务查 88 号商品：Caffeine 本地缓存 → Redis → MySQL，再改一次价（399.00 → 359.00），看三级缓存怎么跟上。
`ProductDetail` 用 4 组对象模拟 4 个实例，各有自己的 Caffeine、Lettuce 连接和 HikariCP 连接池。

## 怎么跑

需要 docker（本机不在 docker 组里就用 `DOCKER="sudo docker" ./run.sh`）。

```bash
./run.sh          # 起 Redis 8.10.3（开 AOF）和 MySQL 8.4 两个容器，五段全跑：setup → trace → bench → hot → price
./run.sh hot      # 容器留着，只跑某一段（trace / bench / hot / price）
./run.sh clean    # 删掉两个容器（连同数据卷）
```

| 段 | 做什么 | 对应全景 |
|---|---|---|
| setup | 建 `trade.product` 灌 1 万行；`bf:product` 布隆过滤器放进 1 万个 id | — |
| trace | 实例 A 全冷查一次 88 号，逐站计时；看 Redis 里留下的 key、编码、配置 | 第 1～8、12 站 |
| bench | 本地命中 / Redis GET / MySQL 主键 / 全没命中，各几千次的 p50、p99 | 第 1～7 站 |
| hot | 缓存刚过期，4 实例 × 50 线程同时查：裸查、Caffeine 合并、再加重建锁，各查库几次 | 第 5 站 |
| price | 改价后两个实例多久读到新价：有 Pub/Sub 广播、只靠本地 5 秒过期 | 第 9～11 站 |

## 预期输出（本机 2 核 ARM，JDK 21.0.13，同机容器）

```
== trace：实例 A 第一次查 88 号（本地、Redis 都是空的）
   1  Caffeine getIfPresent                   6 µs  → miss
   2+3 GET product:88                       321 µs  → nil
   4  BF.EXISTS bf:product 88               332 µs  → true
   5  SET lock:product:88 NX PX 3000        332 µs  → OK
   6  SELECT … FROM product WHERE id=88     563 µs  → 648 字节 JSON
   7a SET product:88 EX 1800+随机             378 µs
   7b EVAL 解锁（比较 token 再 DEL）               317 µs
   8  Caffeine put                           30 µs
   一共 2283 µs
   OBJECT ENCODING product:88 = raw，STRLEN = 648，MEMORY USAGE = 805 字节
   cmdstat_get: usec_per_call=2.33        ← 服务端 2 µs，客户端一次往返 300 µs
== bench
   Caffeine 命中        p50 4.1 µs    Redis GET 命中   p50 279 µs
   MySQL 主键 SELECT    p50 358 µs    全没命中          p50 2.3 ms
== hot（每种 5 轮）
   裸查：查库 16 / 6 / 17 / 12 / 5 次
   Caffeine 合并：2 / 2 / 2 / 3 / 3 次
   再加 Redis 重建锁：1 / 1 / 1 / 1 / 1 次
   connected_clients = 4（200 个线程，4 条连接）
== price
   有 Pub/Sub：提交后 6～7 ms 各实例收到失效消息，11～26 ms 读到 359.00
   没有 Pub/Sub：3.3～4.0 s 后才读到，期间旧价被读 3000 多次
```

## 练习

先猜结果，再运行。

1. `hot` 里每个实例 50 个线程改成 500：裸查会打到数据库多少次？加锁以后最慢的请求要等多久？
2. 把 Caffeine 的过期时间改成 30 分钟、去掉 `subscribe()`，跑 `price`：旧价持续多久？再加回订阅，中途 `CLIENT KILL TYPE pubsub`，看漏掉的消息。
3. 把第 9、10 站的顺序反过来（先 DEL 再 UPDATE），插一个“读到旧值后 sleep 50 ms 再回填”的读线程，复现旧价被写回 Redis。
4. `CONFIG SET maxmemory 2mb` 后再回填 1 万个商品，看 SET 报什么错；改成 `allkeys-lfu` 再试。
5. 把 88 号的详情改成 200 KB，看它自己和别的 key 的 GET p99。
