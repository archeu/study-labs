# B+ 树与跳表：两种多层索引，一个省磁盘，一个省旋转

对应页面：[B+ 树与跳表：两种多层索引，一个省磁盘，一个省旋转](https://study.ainiya.de/algo/bplus-skiplist.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.bplus_skiplist` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/bplus-skiplist compile exec:java -Dexec.mainClass=labs.algo.bplus_skiplist.<类名>`
- 类：`BPlusTree`（教学版 B+ 树：插入、分裂、叶子链表、范围查询计读了几个节点）、`SkipList`（仿 Redis ZSet：p = 1/4、32 层、span 算排名）、`IndexBench`（扇出与树高、跳表层数分布与比较次数、TreeMap vs ConcurrentSkipListMap）。
- `IndexBench`：`java -Xmx2g -cp algo/bplus-skiplist/target/classes labs.algo.bplus_skiplist.IndexBench`

## 预期输出

2026-11-18，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

```
找第一个 >= 70：第3层→u47 下到第2层 第2层→u68 下到第1层 → u74
u74 从小到大排第 7，从大到小排第 2
每节点最多    4 个 key：树高 13，范围内 19,229 笔，读了 9,628 个节点
每节点最多 1200 个 key：树高  3，范围内 19,229 笔，读了 35 个节点
层数分布：1 层 75.00%  2 层 18.74%  3 层 4.68%  …… 最高 10 层；平均每个节点 1.33 根前进指针
每次查找平均比较 36.1 次（log₂n = 19.9）
第 3 轮：put TreeMap 191 ms，CSLM 239 ms；get TreeMap 26 ms，CSLM 50 ms
```

## 练习

先猜结果，再运行。

1. 给 `BPlusTree` 加游标分页 `next20(afterKey)`，翻 1000 页看每页读几个节点。
2. 给 `SkipList` 加 `byRank(k)`，`byRank(7)` 应是 u74。
3. 把 `P` 改成 0.5，对比指针数和比较次数。
