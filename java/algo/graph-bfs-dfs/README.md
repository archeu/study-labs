# 图的表示、BFS 与 DFS：从 1024 出发，找出所有关联账户

对应页面：[图的表示、BFS 与 DFS：从 1024 出发，找出所有关联账户](https://study.ainiya.de/algo/graph-bfs-dfs.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.graph_bfs_dfs` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/graph-bfs-dfs compile exec:java -Dexec.mainClass=labs.algo.graph_bfs_dfs.<类名>`
- 类：`AccountGraph`（HashMap 邻接表：BFS 跳数和最短关联路径、显式栈 / 递归 DFS，main 是手推）、`GraphBench`（20 万账户 / 100 万边：HashMap vs CSR 的内存和 BFS 耗时，三个坑）。
- `GraphBench` 要 JOL，在 `java/` 下：

```
CP=algo/graph-bfs-dfs/target/classes:$HOME/.m2/repository/org/openjdk/jol/jol-core/0.17/jol-core-0.17.jar
java -Xmx2g -cp $CP labs.algo.graph_bfs_dfs.GraphBench
```

## 预期输出

2026-11-18，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

```
离 1024 的跳数：{1024=0, 2001=1, 2002=1, 3001=2, 3002=2, 4001=3, 7777=3}
1024 → 7777 的一条最短关联路径：[1024, 2002, 3002, 7777]
DFS（显式栈）访问顺序：[1024, 2001, 3001, 2002, 3002, 7777, 4001]
  HashMap<Long, List<Long>>  79 MB（JOL）
  CSR 两个 int 数组           8.8 MB
BFS 第 3 轮：HashMap 版 542 ms（到达 199,991 个），CSR 版 45.0 ms（到达 199,991 个）
坑 2 出队时才标记：入队 999,996 次（入队时标记只要 199,990 次）
坑 3 10 万个账户的转账链：递归 DFS StackOverflowError，显式栈 DFS 访问 100001 个
```

## 练习

先猜结果，再运行。

1. 给 `bfs` 加 `maxHops`，只看三跳以内。
2. 加一个连着 5 万个账户的“公共 IP”，再在遍历时跳过度数 > 1000 的点。
3. 数连通分量：最大的一片多大、孤立账户多少；边数降到 10 万再看。
