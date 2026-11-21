# 动态规划进阶：背包和序列，一笔资金分给几个策略

对应页面：[动态规划进阶：背包和序列，一笔资金分给几个策略](https://study.ainiya.de/algo/dp-knapsack.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.dp_knapsack` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/dp-knapsack compile exec:java -Dexec.mainClass=labs.algo.dp_knapsack.<类名>`
- 类：`Allocate`（资金分策略的 0-1 背包：二维 / 一维倒序 / 正序的错、按收益率贪心、对拍、预算粒度和耗时、完全背包计数）、`EditDistance`（交易对代码纠错、1 万 × 1 万字符的耗时和内存）。

## 预期输出

2026-11-21，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

```
0-1 背包 = 38，按收益率贪心 = 30，一维正序（错）= 40
对拍 2000 组：二维 / 一维和暴力不一致 0 组；按收益率贪心错 360 组
n=200，预算 1000000 份：400 ms，数组 7812 KB
面额 {1,3,4} 凑 6 的方法数 = 4
你是不是要：BTC-USDT
1 万 × 1 万字符：编辑距离 196，735 ms，二维表约 381 MB
```

## 练习

先猜结果，再运行。

1. 用二维表倒推投了哪几个策略（应为 B、C）。
2. 恰好用完预算：起点改负无穷，预算 7 份应无解。
3. 编辑距离只留两行，结果仍是 196。
