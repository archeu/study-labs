# 4 台机器各存一份 88 号：Caffeine 加 Redis 两级缓存

对应页面：[4 台机器各存一份 88 号：Caffeine 加 Redis 两级缓存](https://archeu.github.io/study-notes/redis/cache-multilevel.html)（Java 地图 J07 · 全景“一次商品详情查询”第 1、8、11 站）

交易靶场商品服务的两级缓存：一级 Caffeine（每个实例一份），二级 Redis（大家共用），改价后用 Redis Pub/Sub 广播让各实例作废本地那份。
为了只看两级缓存，数据库用一个 Map 代替（每次查 sleep 1 ms），省掉了布隆过滤器和重建锁（全景第 4、5 站，见 `java/pano-j07-product-detail-cache`）。

| 类 | 做什么 |
|---|---|
| `TwoLevelCache` | ⑤ 的最小案例：`get` 走 L1 → Redis → DB，`evict` 先 DEL 再 PUBLISH；订阅连接断线时、补订确认时各清一次本地 |
| `Lab` | 三段实验：`quick` / `gap` / `race` |

## 怎么跑

需要 docker（本机不在 docker 组里就用 `DOCKER="sudo docker" ./run.sh`）。代码按 JDK 8 写（Caffeine 2.9.3、Lettuce 6.8.2），JDK 8 / 11 / 17 / 21 都能跑。

```bash
./run.sh          # 起 Redis 8.10.3 容器（端口 63798），三段全跑
./run.sh race     # 容器留着，只跑某一段（quick / gap / race）
./run.sh clean    # 删掉容器
```

| 段 | 做什么 | 对应页面 |
|---|---|---|
| quick | A、B 两个实例读 88 号，改价，再读：每次在哪一层拿到的 | ⑤ |
| gap | 订阅连接被 `CLIENT KILL TYPE pubsub` 断开，紧接着改价：失效消息没人收到。A 只靠本地 5 秒过期，B 断线和补订时各清一次本地 | ⑥ 第一圈 |
| race | 本实例正从 Redis 读 88 号（读到旧价后卡 200 ms），这时失效消息到了：`getIfPresent` + `put` 对比 `get(key, loader)` | ④、⑥ 第一圈 |

## 预期输出（本机 2 核 ARM，JDK 17.0.21，Redis 8.10.3 同机容器）

```
== quick：两个实例，本地缓存 5 秒，Redis 30 分钟起
   A 第一次查     DB      3559 µs  price=399.00
   A 再查一次     L1        32 µs  price=399.00
   B 第一次查     Redis   1081 µs  price=399.00
   改价：UPDATE 后 DEL + PUBLISH，2944 µs，2 个实例收到消息
   A 改价后查     DB      3029 µs  price=359.00
   B 改价后查     Redis    767 µs  price=359.00
   B 再查一次     L1        78 µs  price=359.00
== gap（每段 3 轮，数字几乎一样）
   第 1 轮：断开 2 条订阅连接；PUBLISH 收到的实例 = 0
      A（只靠本地过期）   4941 ms 后读到 359.00，期间读到旧价 4618 次
      B（断线、补订都清）    0 ms 后读到 359.00，期间读到旧价    0 次
      订阅确认：A 2 次，B 2 次（第 2 次就是重连后自动补订）
== race
   getIfPresent+put  DEL+PUBLISH 1 ms，监听器里 invalidate 用了 0.0 ms；加载结束后旧价又留了 4983 ms
   get(key, loader)  DEL+PUBLISH 1 ms，监听器里 invalidate 用了 199 ms；加载结束后旧价又留了 2～5 ms（读到 0 次）
```

`get(key, loader)` 那一行，10 轮里有 2 轮 `DEL+PUBLISH` 变成了 200 ms：监听器在 Lettuce 的 IO 线程上等 invalidate，同一个线程上的其他连接也跟着等。

## 练习

先猜结果，再运行。

1. 把 `TwoLevelCache` 的本地过期时间从 5 秒改成 30 分钟，跑 `gap`：A 的旧价会留多久？这说明本地过期时间在两级缓存里扮演什么角色？
2. 给 `TwoLevelCache` 加一个版本号：Redis 里存 `product:88:ver`，改价时 `INCR`；本地每条带上版本，每 1 秒批量 `MGET` 一次版本号对账，不一致就作废。去掉 B 的断线清理，再跑 `gap`，旧价最多留多久？
3. 把 `message()` 里的 `l1.invalidate(key)` 改成交给一个单线程 `Executor` 去做，再跑 `race` 10 轮：`DEL+PUBLISH` 还会出现 200 ms 吗？旧价会不会重新出现？为什么？
