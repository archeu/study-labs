# 排序：快排、归并、TimSort 和稳定性

对应页面：[排序：快排、归并、TimSort 和稳定性](https://study.ainiya.de/algo/sorting.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.sorting` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/sorting compile exec:java -Dexec.mainClass=labs.algo.sorting.<类名>`
- 类：`Sorts`（Lomuto 划分、教学版快排、稳定归并，main 是手推）、`Reconcile`（两份流水排序后双指针对账；稳定 vs 不稳定）、`Pitfalls`（有序输入退化、String 成交号、违反约定的比较器）、`SortBench`（500 万成交号：手写快排 / long[] / parallelSort / Long[] TimSort，随机 vs 几乎有序）。
- `SortBench` 别在 Maven 进程里跑：`java -Xms2g -Xmx2g -cp algo/sorting/target/classes labs.algo.sorting.SortBench`

## 预期输出

2026-11-18，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

```
基准 4 放到 3    [3, 1, 2, 4, 9, 8, 7, 5]
撮合（不稳定快排）：[..., 103:更正:480, 103:成交:500, ...]  → 多报两条金额不一致
n=100,000：StackOverflowError（递归深度 = n）
随机     手写快排   403   Arrays.sort(long[])   431   parallelSort   430   Arrays.sort(Long[]) TimSort  4649
几乎有序   手写快排   155   Arrays.sort(long[])    67   parallelSort    67   Arrays.sort(Long[]) TimSort    36
```

## 练习

先猜结果，再运行。

1. 给比较器加上原始序号，让不稳定的快排也能正确对账。
2. 把 `tradeId << 20 | 序号` 编进 long[] 做稳定排序，对比 TimSort 的对账结果。
3. 在归并里数逆序对，对比“几乎有序”和随机数据。
