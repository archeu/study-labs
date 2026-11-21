# 题型地图：看到题怎么认出用哪招，12 站换成 12 组面试题

对应页面：[题型地图：看到题怎么认出用哪招，12 站换成 12 组面试题](https://study.ainiya.de/algo/problem-map.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.problem_map` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/problem-map compile exec:java -Dexec.mainClass=labs.algo.problem_map.<类名>`
- 类：`Backlog`（LeetCode 1801 积压订单，两个堆，就是全景第 5 站）、`Budget`（本机 1 秒能做多少次 O(n) / O(n log n) / O(n²) 操作）。

## 预期输出

2026-11-21，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

```
示例 1：6
示例 2：999999984
全景第 5 站：4 手还挂着（卖簿 3 手 + 买簿剩下的 1 手）
第 3 轮：1 亿次简单循环 156 ms；100 万个 int 排序 76 ms；n=1 万的两两配对（5000 万对）101 ms
```

## 练习

先猜结果，再运行。

1. 给 1801 加撤单（延迟删除，或换 TreeMap）。
2. 560 在全正数时用滑动窗口，和前缀和版对拍。
3. 给撮合引擎加第 13 站，自己出一道题，写出规模和题型。
