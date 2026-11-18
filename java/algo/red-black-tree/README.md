# 二叉搜索树与红黑树：撤单多、要盘口，为什么换 TreeMap

对应页面：[二叉搜索树与红黑树：撤单多、要盘口，为什么换 TreeMap](https://study.ainiya.de/algo/red-black-tree.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.red_black_tree` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/red-black-tree compile exec:java -Dexec.mainClass=labs.algo.red_black_tree.<类名>`
- 类：`AskBook`（TreeMap<价位, 同价队列> + HashMap<订单号, 订单> 的卖盘）、`RbTree`（只有插入的红黑树，打印每一步修复）、`BookBench`（反射量真实 TreeMap 的树高、PriorityQueue vs TreeMap 撤单、三个坑）。
- `BookBench` 要开放 java.util 给反射：`java -Xmx1g --add-opens java.base/java.util=ALL-UNNAMED -cp algo/red-black-tree/target/classes labs.algo.red_black_tree.BookBench`

## 预期输出

2026-11-18，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

```
  叔叔是黑的（或空）：在 10150 处旋转 + 变色  50(20,100*(80,200(150*,300*)))
树高 4（第 13 课不平衡的树，同样的插入顺序，树高 7）
n=1,000,000：一路上涨 树高 37，随机 树高 24，上界 2·log₂(n+1) = 39.9，log₂n = 19.9
第 2 轮：PriorityQueue 868 ms，TreeMap 7.1 ms，最优价一致 true
坑 1 同价直接当 key：{10050=#4 李四 2 手}  ← #2 被覆盖了
坑 2 BigDecimal 100.5 和 100.50：TreeSet 大小 1，HashSet 大小 2
坑 3 遍历 headMap 视图时删原 map：ConcurrentModificationException
```

## 练习

先猜结果，再运行。

1. 配一个降序的买盘，写 `match(买单)`，用 `pollFirstEntry` 一档档吃。
2. 写 `amend(id, newPrice)`：先删再插，排到新价位队尾。
3. 给 `RbTree` 加旋转、变色计数器，插 100 万个一路上涨的价位，看平均每次插入转几次。
