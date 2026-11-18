# 哈希表：从 hashCode 到冲突和扩容

对应页面：[哈希表：从 hashCode 到冲突和扩容](https://study.ainiya.de/algo/hash-table.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.hash_table` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/hash-table compile exec:java -Dexec.mainClass=labs.algo.hash_table.<类名>`
- 类：`LongOpenHashSet`（long 原语开放寻址集合，线性探测 + 墓碑，main 是手推）、`ProbeBench`（负载因子与探测次数、雪花 ID 打散前后）、`MemBench`（500 万单号：HashSet<Long> 对比开放寻址）。
- 测速类别在 Maven 进程里跑，编译后直接起 JVM（在 `java/` 下）：

```
CP=algo/hash-table/target/classes:$HOME/.m2/repository/org/openjdk/jol/jol-core/0.17/jol-core-0.17.jar
java -Xmx1g -cp $CP labs.algo.hash_table.ProbeBench
java -Xms3g -Xmx3g -cp $CP labs.algo.hash_table.MemBench hashset
java -Xms3g -Xmx3g -cp $CP labs.algo.hash_table.MemBench open
```

## 预期输出

2026-11-18，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

`ProbeBench`：

```
α      命中平均探测(实测/公式)    未命中平均探测(实测/公式)
0.50     1.51 /   1.50                  2.50 /     2.50
0.75     2.50 /   2.50                  8.53 /     8.50
0.95    10.45 /  10.50                195.10 /   200.50
雪花 ID 20000 个，直接 key & mask：平均每次 add 探测 10000.5 次，用时 451 ms
雪花 ID 20000 个，先 fmix64 打散：平均每次 add 探测 1.0 次，用时 1 ms
```

`MemBench`（JOL 数出来的内存）：

```
hashset  放 5000000 个再查重 5000000 次：用时 6066 ms，JOL 数出 314 MB，每个单号 62.7 字节
open     放 5000000 个再查重 5000000 次：用时 819 ms，JOL 数出 75 MB，每个单号 15.1 字节
```

## 练习

先猜结果，再运行。

1. 按交易日滚动：持有今天、昨天两张表，换日时扔掉昨天那张。
2. 把 `remove` 改成往回挪（backward shift），删 11 后查 27 只探 2 次。
3. 把 `fmix64` 换成 Fibonacci hashing，雪花 ID 的探测次数也应接近 1。
