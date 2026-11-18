# 前缀和与差分：任意时间段成交量 O(1)

对应页面：[前缀和与差分：任意时间段成交量 O(1)](https://study.ainiya.de/algo/prefix-sum.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.prefix_sum` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/prefix-sum compile exec:java -Dexec.mainClass=labs.algo.prefix_sum.<类名>`
- 类：`VolumeIndex`（秒桶前缀和、差分算每秒挂单数、前缀和 + 哈希数合计为 K 的段，main 是手推）、`Pitfalls`（忘了空前缀、int 前缀和、double 前缀和，以及 100 万次查询的测速，约半分钟）。

## 预期输出

2026-11-18，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

```
[3, 7) 秒成交量 = P[7] - P[3] = 77 - 19 = 58
每秒挂单数 [0, 1, 2, 3, 2, 2, 1, 2, 1, 0]
净流入 [3, -1, -2, 5, -3, 3, 0]，合计恰好为 0 的连续段有 5 个
合计为 0 的段：漏了 {0:1} 得到 4，正确是 5
最后 10 笔手续费：double 前缀和相减 = 1.000000000058208（应为 1.0）
第 3 轮：每次扫 10598 ms（每次 10.6 µs）  前缀和 建 0.32 ms + 查 3.9 ms（每次 3.9 ns）  一致 true
```

## 练习

先猜结果，再运行。

1. 买 +1、卖 −1，用前缀和 + 哈希找买卖单数相等的最长一段。
2. 给 `VolumeIndex` 加 `append`，O(1) 追加一秒。
3. 写一个树状数组，和前缀和对拍。
