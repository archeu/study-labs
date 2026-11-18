# 二分查找：边界怎么写才不出错

对应页面：[二分查找：边界怎么写才不出错](https://study.ainiya.de/algo/binary-search.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.binary_search` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/binary-search compile exec:java -Dexec.mainClass=labs.algo.binary_search.<类名>`
- 类：`TradeLog`（lowerBound / upperBound，main 是手推）、`Pitfalls`（溢出、死循环、重复元素、负返回值、降序数据）、`SearchBench`（3000 万笔从头扫 vs 二分；ArrayList vs LinkedList）。
- `SearchBench` 别在 Maven 进程里跑：`java -Xmx2g -cp algo/binary-search/target/classes labs.algo.binary_search.SearchBench`

## 预期输出

2026-11-18，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

```
[20:00:00, 20:05:00] 闭区间：下标 [4, 13)，共 9 笔
[20:00:00, 20:05:00) 左闭右开：下标 [4, 11)，共 7 笔（20:05:00 那两笔不算）
(lo + hi) / 2：第 2 步 lo=1073741824 hi=2147483647，mid 算成 Index -536870912 out of bounds
Arrays.binarySearch(a, 200) = 3，lowerBound = 1
第 3 轮  从头扫：每次 10.35 ms   二分：每次 959 ns   结果一致：true
20 万个元素  Collections.binarySearch  ArrayList 每次 1284 ns   LinkedList 每次 935097 ns
```

## 练习

先猜结果，再运行。

1. 写对拍：随机有序数组上比对 lowerBound / upperBound 和从头扫，再故意改坏 `lo = mid + 1` 看能不能抓到。
2. 卖盘升序、买盘降序，各写“能成交的档位有几档”。
3. 二分答案：10 批清算任务、8 小时期限，求每小时最少处理多少条。
