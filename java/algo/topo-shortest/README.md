# 拓扑排序与最短路径：清算谁先谁后，换汇走哪条路

对应页面：[拓扑排序与最短路径：清算谁先谁后，换汇走哪条路](https://study.ainiya.de/algo/topo-shortest.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.topo_shortest` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/topo-shortest compile exec:java -Dexec.mainClass=labs.algo.topo_shortest.<类名>`
- 类：`SettleDag`（清算任务依赖的 Kahn 拓扑排序和环检测）、`CurrencyRoute`（换汇图上的 Dijkstra（延迟删除）、“入队就定下来”的错误写法、负边时和 Bellman-Ford 对比）、`RouteBench`（2 万点 20 万边，堆版 vs O(V²) 版）。

## 预期输出

2026-11-18，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

```
顺序：[成交汇总, 行情收盘价, 手续费, 持仓, 资金, 保证金, 风险度, 日终报表]（8/8）
加了 风险度 → 持仓 以后：[成交汇总, 行情收盘价, 手续费]（3/8）← 少了，说明有环
BTC → ETH 最低成本 19 bp，路线 [BTC, USDC, USDT, ETH]；堆里弹出的旧条目 3 个
错误写法（入队就定下来）：BTC → ETH = 30 bp
有负边时 A → D：Dijkstra 3，Bellman-Ford 2
第 2 轮：20,000 个点 200,000 条边，堆版 35 ms，O(V²) 版 1441 ms，结果一致 true
```

## 练习

先猜结果，再运行。

1. 结果数不等于任务数时抛异常并列出卡住的任务。
2. 给任务加耗时，算关键路径和最早完成时间。
3. 边权换成 -log(1 - 费率)，再加一条返佣负边，对比 Dijkstra 和 Bellman-Ford。
