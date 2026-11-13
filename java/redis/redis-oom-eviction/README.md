# 第 617 个商品写不进去：Redis 写满以后的 OOM、淘汰与过期

对应页面：[第 617 个商品写不进去：Redis 写满以后的 OOM、淘汰与过期](https://archeu.github.io/study-notes/redis/redis-oom-eviction.html)（Java 地图 J07 · [全景：一次商品详情查询](https://archeu.github.io/study-notes/java/pano-j07-product-detail-cache.html) 第 3、12 站，牵连第 5、7 站）

两个类：

- `ProductCache`：交易靶场读详情，第 2～7 站（GET → SET NX PX 抢锁 → 查库 → SET PX 回填 → Lua 解锁）。`fallback=true` 时 Redis 写报错就直接查库（“降级”）。
- `Lab` 三段：
  - `oom`：`noeviction` + `maxmemory 2mb`，回填 1 万个商品，看第几个失败、败在哪一站；内存满时逐条试 GET / SET NX / SET / EXPIRE / EVAL 解锁 / DEL；20 个请求同时查一个没缓存的商品，库被查几次。
  - `policy`：`noeviction` / `allkeys-lru` / `allkeys-lfu` / `volatile-lru` 各回填一遍，看淘汰了多少、热 key 88 号和一把拿着的锁还在不在、剩下的是老商品还是新商品。
  - `expire`：所有商品都带 `PX 2000` 写满，到点以后每 100 ms 看一次：过期了多少、内存降了多少、新商品写不写得进。

## 怎么跑

```bash
./run.sh            # 起 redis:8.10.3-alpine（63798，除端口外全默认），三段都跑，约 3 分钟
./run.sh expire     # 容器留着，只跑某一段（oom / policy / expire）
./run.sh clean      # 删容器
```

本机不在 docker 组里：`DOCKER="sudo docker" ./run.sh`。IDE 里直接跑 `Lab`，参数 `oom` / `policy` / `expire`，可加 `-Dport= -Dmax=4mb -Dn=20000`。

## 预期输出

2 核 ARM（aarch64），Docker 里 Redis 8.10.3（jemalloc 5.3.0），Lettuce 6.8.2，代码按 JDK 8 写、JDK 17 上跑。“查库”是假的，每次 sleep 2 ms，详情约 650 字节。具体数字每次差几个，结论不变。

```
== Redis 8.10.3，maxmemory 2mb，商品 10000 个 ==

[oom] 空库 used_memory 1390.2 KB（还没存任何商品）
  回填 10000 个：成功 617，失败 9383；第一次失败在商品 617
  报错原文：OOM command not allowed when used memory > 'maxmemory'.
  此时 dbsize 617，used_memory 2072.3 KB，evicted_keys 0
  内存满时逐条试：
    GET product:1         → 命中
    SET lock NX PX 3000   → OOM command not allowed when used memory > 'maxmemory'.
    SET product:9999      → OOM command not allowed when used memory > 'maxmemory'.
    EXPIRE product:1 60   → true
    EVAL 解锁（GET+DEL）  → 0
    DEL product:1         → 1
    删一个以后 SET 能进吗 → OOM command not allowed when used memory > 'maxmemory'.
  20 个请求同时查没缓存的商品 9999：
    内存没满（maxmemory 0）      拿到详情 20，报错  0，查库  1 次
    内存满了，不降级（抛异常）   拿到详情  0，报错 20，查库  0 次
    内存满了，降级（报错就查库） 拿到详情 20，报错  0，查库 20 次

[policy] 先把 88 号读热（200 次），拿着 lock:product:20001（回填范围外，PX 600000），再回填 1～10000，每回填 10 个读一次 88 号
  策略            OOM次数  淘汰数  剩下  88号  锁20001  1～1000剩  后1000剩  耗时
  noeviction        9353       0   648  在     在             646         0  26.9 s
  allkeys-lru          0    9360   641  在     没了              0       633  29.1 s
  allkeys-lfu          0    9359   642  在     没了              0       445  28.8 s
  volatile-lru         0    9360   641  在     没了              0       628  28.8 s

[expire] noeviction，736 个商品全带 PX 2000 写满（用了 478 ms），然后每 100 ms 看一次
  距第一次写入   dbsize  used_memory  expired_keys  SET 新商品
    1801 ms       736    2095.6 KB             0  OOM
    1907 ms       736    2120.7 KB             0  OOM
    2012 ms       736    2122.7 KB             0  OOM
    2121 ms       735    2122.0 KB             1  OOM
    2225 ms       733    2120.3 KB             3  OOM
    2329 ms       562    1984.0 KB           174  OK
    2433 ms       242    1729.5 KB           494  OK
    2537 ms        40    1553.0 KB           696  OK
    2641 ms         0    1519.5 KB           736  OK
    2745 ms         0    1519.4 KB           736  OK
```

几处要看的：

- `oom`：第 617 个商品的锁抢到了（那条 SET NX 是压线前最后一条），回填的 SET 被拒，报错出在 `ProductCache.java:56`（第 7 站）。之后偶尔有一两个趁内存回落塞进去（这次是 5116 号）。
- `policy`：三种会淘汰的策略里，`lock:product:20001` 都被赶走了，`volatile-lru` 也一样，因为锁带着过期时间。
- `expire`：第一批 key 2000 ms 到点，到 2225 ms 只删掉 3 个，新商品还是 OOM；到 2329 ms 才写得进。

## 练习

先猜结果，再运行。

1. `policy` 里把 88 号改成“只在开头读 200 次，回填时不再读”。`allkeys-lru` 和 `allkeys-lfu` 下 88 号还在吗？（提示：回填 30 秒，LFU 计数按分钟衰减）
2. `ProductCache` 改成锁和缓存分两个 Redis 实例：缓存实例 `allkeys-lfu`，锁实例 `noeviction`。重跑 `policy`，锁还会丢吗？
3. `expire` 加一个参数，把 `active-expire-effort` 调到 10，再看 OOM 持续多久；同时用 `INFO stats` 的 `expired_time_cap_reached_count` 看慢周期有没有超时。
