# 数组为什么快：连续内存、缓存行和 O(1) 下标

对应页面：[数组为什么快：连续内存、缓存行和 O(1) 下标](https://study.ainiya.de/algo/array-why-fast.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.array_why_fast` 包下的 main。
- `Layout`（JOL 看数组布局、量内存）：`cd java && mvn -q -pl algo/array-why-fast compile exec:java -Dexec.mainClass=labs.algo.array_why_fast.Layout`
- `Speed`（耗时粗测，不依赖 JOL；别在 Maven 进程里跑，直接起 JVM）：编译后 `java -cp algo/array-why-fast/target/classes labs.algo.array_why_fast.Speed`

## 预期输出

2026-11-18，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。耗时是粗测（热身 3 次、取 5 次最快），跑两遍相差不到 2%，看倍数不看绝对值。

```
== 1 把 400 万个数加一遍（都是 O(n)）==
  int[]                                   2.72 ms
  ArrayList<Integer>                      6.26 ms
  ArrayList<Integer>（引用打乱）               45.49 ms
  LinkedList<Integer>                    14.74 ms

== 2 按下标访问：顺序 vs 随机（都是 O(1) 下标）==
  顺序：big[seq[i] * 8]                      9.47 ms
  随机：big[rnd[i] * 8]                     45.50 ms

== 3 二维数组 4096×4096：按行 vs 按列 ==
  按行：g[i][j]                             12.96 ms
  按列：g[j][i]                            178.51 ms

== 4 步长实验：每隔 k 个 int 改一个（128 MB 数组）==
  k = 1（改了 33554432 个）                   32.23 ms
  k = 2（改了 16777216 个）                   19.32 ms
  k = 4（改了 8388608 个）                    12.65 ms
  k = 8（改了 4194304 个）                    11.12 ms
  k = 16（改了 2097152 个）                    9.67 ms
  k = 32（改了 1048576 个）                    7.83 ms
  k = 64（改了 524288 个）                     4.05 ms

== 5 取第 200 万个元素 ==
  ArrayList.get(n/2) × 1000               0.06 ms
  LinkedList.get(n/2) × 10               69.02 ms

(sink=0)
```

JOL 0.17（无 Instrumentation 时会先打两行 WARNING，不影响结果）：

```
== 1 int[4] 的布局 ==
[I object internals:
OFF  SZ   TYPE DESCRIPTION               VALUE
  0   8        (object header: mark)     0x0000000000000001 (non-biasable; age: 0)
  8   4        (object header: class)    0x00006c28
 12   4        (array length)            4
 16  16    int [I.<elements>             N/A
Instance size: 32 bytes
Space losses: 0 bytes internal + 0 bytes external = 0 bytes total
== 2 一个 Integer 对象 ==
java.lang.Integer object internals:
OFF  SZ   TYPE DESCRIPTION               VALUE
  0   8        (object header: mark)     0x0000000000000001 (non-biasable; age: 0)
  8   4        (object header: class)    0x000413b8
 12   4    int Integer.value             1000
Instance size: 16 bytes
Space losses: 0 bytes internal + 0 bytes external = 0 bytes total
== 3 存 100 万个整数，一共占多少字节（含引用到的所有对象）==
int[]                  4,000,016
ArrayList<Integer>    20,000,040
LinkedList<Integer>   40,000,032
```

## 练习

先猜结果，再运行。

1. **一维代替二维**：把 `int[][] g` 换成 `int[m * m]`，用 `g[i * m + j]` 按行、按列各测一次。先预测按列还慢多少。
2. **自己写 IntList**：`int[]` + `size` + 1.5 倍扩容；和 `ArrayList<Integer>` 比求和时间，再用 `GraphLayout` 比内存（应接近 4 MB）。
3. **把链表也打乱**：`Collections.shuffle(ll)` 后再遍历求和，预测它从 14.7 ms 变成什么量级，再跑。
