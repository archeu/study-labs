# 648 字节的详情占 805 字节：Redis 8 的 kvobj、embstr 新门槛与存 String 还是 Hash

对应页面：[648 字节的详情占 805 字节：Redis 8 的 kvobj、embstr 新门槛与存 String 还是 Hash](https://archeu.github.io/study-notes/redis/redis-encoding-memory.html)（Java 地图 J07 · [全景：一次商品详情查询](https://archeu.github.io/study-notes/java/pano-j07-product-detail-cache.html) 第 7 站，也涉及第 3、4 站）

`Lab` 三段：

- `one`：88 号 648 字节 JSON 回填一次（带不带 TTL），看 `OBJECT ENCODING` 和 `MEMORY USAGE`；顺带 `stock:88 = 1200`、`price:88 = 399.00`。
- `embstr`：key 3、10、24、40 字节时，值最长多少字节还是 embstr。
- `shapes`：同一份详情四种存法（String JSON、Hash 5 字段、Hash 短字段 + 详情 String、`JSON.SET`），各存 5 万个商品，`used_memory` 增量 ÷ 5 万 = 每个商品实际占多少。`-DsameDetail=true` 让所有商品用同一段详情；`-Dn=` 改个数。

## 怎么跑

```bash
./run.sh            # 起 redis:8.10.3-alpine（63798）和 redis:7.2-alpine（63772），三段两个版本都跑
./run.sh shapes     # 容器留着，只跑某一段
./run.sh clean      # 删容器
```

本机不在 docker 组里：`DOCKER="sudo docker" ./run.sh`。IDE 里直接跑 `Lab`，加 `-Dport=63798` 或 `-Dport=63772`。

## 预期输出

2 核 ARM，Docker 里 Redis 8.10.3 / 7.2.17（jemalloc 5.3.0，除端口外全默认），Lettuce 6.8.2，代码按 JDK 8 写、JDK 17 上跑。`one`、`embstr` 的数字是确定的；`shapes` 每遍差 0～5 字节，Hash 那行在新起的容器上跑过一次 1021。

```
== Redis 8.10.3，jemalloc-5.3.0 ==
[one] 88 号 JSON 648 字节（detail 576 字节）
  SET 不带 TTL     : raw    MEMORY USAGE 797
  SET ... EX 1843 : raw    MEMORY USAGE 805   STRLEN 648
  SET stock:88 1200: int    MEMORY USAGE 27
  SET price:88 399.00: embstr MEMORY USAGE 40
[embstr] key 长度 → 值最长多少字节仍是 embstr
  key  3 字节 → 值 ≤ 38 字节
  key 10 字节 → 值 ≤ 31 字节
  key 24 字节 → 值 ≤ 17 字节
  key 40 字节 → 值 ≤  1 字节
[shapes] 每种存 50000 个商品，TTL 1800 s，详情各不相同；…
  string     编码 raw       MEMORY USAGE(88 号)   805   每个商品实际   838 字节
  hash       编码 hashtable MEMORY USAGE(88 号)   992   每个商品实际  1004 字节
  hash-split 编码 listpack  MEMORY USAGE(88 号)   773   每个商品实际   853 字节
  json       编码 raw       MEMORY USAGE(88 号)   792   每个商品实际   944 字节

== Redis 7.2.17 ==
  SET ... EX 1843 : raw  MEMORY USAGE 824；stock:88 → 56；price:88 → 72
  embstr：key 多长都是值 ≤ 44 字节
  string 868 · hash 1105 · hash-split 912（7.2 没有 JSON.SET，这一行不跑）
```

`-DsameDetail=true` 时 8.10 上 JSON 只有 278 字节/个，其余三种不变。

## 练习

先猜结果，再运行。

1. 把 `DETAIL` 末尾去掉 5 个汉字（15 字节），88 号 JSON 变成 633 字节。8.10 和 7.2 的 `MEMORY USAGE` 各是多少？（页面④的计算器能先算）
2. 在全景的 `ProductDetail` 里，第 7 站改成 `HSET product:h:88` 短字段 + `SET detail:88`，第 10 站改价两个都删。第 7 站慢了多少？要不要用 Pipeline？
3. `-DsameDetail=true` 跑 `shapes`，再改成只有前 100 个商品详情相同，JSON 的数字怎么变？
