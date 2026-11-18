# 并查集：账户一对对合并成团伙，查“是不是一伙”几乎 O(1)

对应页面：[并查集：账户一对对合并成团伙，查“是不是一伙”几乎 O(1)](https://study.ainiya.de/algo/union-find.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.union_find` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/union-find compile exec:java -Dexec.mainClass=labs.algo.union_find.<类名>`
- 类：`UnionFind`（按大小合并、路径压缩可分开开关；账户号映射成下标；main 是六条关联事件的手推）、`UfBench`（最坏输入下四个版本对比、100 万条关联边流式合并 vs BFS、不找根的坑）。
- `UfBench`：`java -Xmx1g -cp algo/union-find/target/classes labs.algo.union_find.UfBench`

## 预期输出

2026-11-18，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

```
1024 和 7777 一伙吗：true；1024 和 5005：false
parent 数组（下标对应 [1024, 2001, 2002, 3001, 3002, 4001, 7777, 5005]）：[0, 0, 0, 0, 2, 0, 0, 7]
裸的         合并后最深  29,999 层   查 30,000 次共走   449,985,000 步    1,088 ms
按大小 + 路径压缩 合并后最深       1 层   查 30,000 次共走        29,999 步        1 ms
第 2 轮：并查集 137 ms，最后剩 161,515 个团伙
先并 0—1、再并 1—2，0 和 2 一伙吗：false（应为 true）
```

## 练习

先猜结果，再运行。

1. 加 `groupSize(x)`，只拦超过 5 人的团伙。
2. 数组可扩容，新账户随时发下标。
3. 存下关联边，写 `rebuild(撤销的边)`。
