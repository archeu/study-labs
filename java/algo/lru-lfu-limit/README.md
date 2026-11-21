# 设计题：LRU、LFU 与限流，行情快照缓存和每账户每秒 20 单

对应页面：[设计题：LRU、LFU 与限流，行情快照缓存和每账户每秒 20 单](https://study.ainiya.de/algo/lru-lfu-limit.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.lru_lfu_limit` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/lru-lfu-limit compile exec:java -Dexec.mainClass=labs.algo.lru_lfu_limit.<类名>`
- 类：`LruCache`（哈希表 + 双向链表 + 哨兵）、`LfuCache`（次数桶 + minFreq，O(1)）、`HitRate`（FIFO / LRU / LFU 在稳定热点、热点 + 扫描、热点漂移三种流量下的命中率）、`AccountLimiter`（每账户懒补充令牌桶，账户表用 LinkedHashMap 做 LRU 上限）。

## 预期输出

2026-11-21，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

```
put XRP（踢 BTC） → [XRP, SOL, DOGE]，get ETH = null
put DOGE（踢次数最少的 SOL）→ freq={BTC=3, ETH=2, DOGE=1} minFreq=1
  A 稳定热点         FIFO  34.4%   LRU  39.1%   LFU  49.7%
  B 热点 + 报表扫描    FIFO  33.0%   LRU  36.6%   LFU  49.8%
  C 热点漂移         FIFO  34.4%   LRU  39.1%   LFU  25.9%
账户 1024 在同一毫秒连发 30 单：放行 20，拒绝 10
100 万个不同账户各来一次，账户表上限 1000：表里现在 1000 个账户
```

## 练习

先猜结果，再运行。

1. 线程安全的 LRU：加 `synchronized`，8 线程随机读写后核对 map 大小和链表长度；去掉锁看会坏成什么样。
2. LFU 加衰减：每 1 万次访问次数减半，重跑“热点漂移”。
3. 令牌桶容量改 5、速率仍每秒 20：突发只放 5 单，持续每 50 ms 一单全放。
