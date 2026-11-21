# 字符串：KMP 与 Trie，输入 BT 就补全交易对

对应页面：[字符串：KMP 与 Trie，输入 BT 就补全交易对](https://study.ainiya.de/algo/kmp-trie.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.kmp_trie` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/kmp-trie compile exec:java -Dexec.mainClass=labs.algo.kmp_trie.<类名>`
- 类：`SymbolTrie`（交易对补全：全表扫 / TreeMap 前缀区间 / Trie，5 万个代码 × 10 万次查询）、`Kmp`（next 数组、最坏输入下朴素和 KMP 的比较次数、和 `String.indexOf` 比耗时）。

## 预期输出

2026-11-21，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

```
输入 BT  → [BTC-EUR, BTC-USDC, BTC-USDT]
第 2 轮 5 万个代码 × 10 万次补全（取前 10 个）：全表扫 39011 ms，TreeMap 区间 95 ms，Trie 274 ms
next("ABABC") = [0, 0, 1, 2, 0]
n=1000000 m=1000（最坏）：朴素比较 999001000 次，KMP 1999001 次
第 3 轮：最坏输入 indexOf 552 ms / KMP 5 ms；正常日志 indexOf 0.75 ms / KMP 2.87 ms
```

## 练习

先猜结果，再运行。

1. 逐字输入：会话对象记住当前节点，每敲一个字只走一步。
2. 前缀计数：节点加 `count`，“以 BT 开头的有几个”O(L)。
3. 流式 KMP：每次喂一个 byte，块边界上的匹配不能漏。
