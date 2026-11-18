# 双指针与滑动窗口：每个指针只往前走

对应页面：[双指针与滑动窗口：每个指针只往前走](https://study.ainiya.de/algo/two-pointers.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.two_pointers` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/two-pointers compile exec:java -Dexec.mainClass=labs.algo.two_pointers.<类名>`
- 类：`Windows`（最短放量段、60 秒成交量、快慢去重、对撞凑单，main 是手推）、`Pitfalls`（负数、if 缩窗、int 溢出，以及双指针 vs 暴力测速）。

## 预期输出

2026-11-18，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

```
答案：长度 3，从下标 3 开始；暴力对拍 [3, 3]
过去 60 秒成交量：[10, 15, 23, 15, 22, 8]
净流入 [5, -3, 1, 6, -10, 8, 4]，最短一段合计 >= 12：双指针 长度 0 起点 -1；暴力 长度 2 起点 5
[1, 1, 1, 1, 20, 1] 合计 >= 20：if 版本得到 5，while 版本得到 1
n=100,000 第 2 轮：双指针 0.57 ms，暴力 1396 ms，结果一致 true（长度 36162）
```

## 练习

先猜结果，再运行。

1. 改成“最长一段合计 ≤ K”，[3,1,4,1,5,9,2,6,5,3]、K = 10 的答案是 4。
2. 每笔带用户号，找“最多 2 个不同用户”的最长连续一段。
3. 写对拍：元素 0～9 全过，改成 −5～9 后看对拍报错。
