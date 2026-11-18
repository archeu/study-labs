# 递归与二叉树遍历：盘口从优到劣，就是一次中序遍历

对应页面：[递归与二叉树遍历：盘口从优到劣，就是一次中序遍历](https://study.ainiya.de/algo/recursion-tree.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.recursion_tree` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/recursion-tree compile exec:java -Dexec.mainClass=labs.algo.recursion_tree.<类名>`
- 类：`PriceTree`（不平衡的价位二叉搜索树：递归前中后序、显式栈中序取前 k 档、层序、树高，main 是手推）、`Pitfalls`（退化树的递归深度、fib 的重复子问题）。
- 想看栈大小的影响：`java -Xss8m -cp algo/recursion-tree/target/classes labs.algo.recursion_tree.Pitfalls`

## 预期输出

2026-11-18，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21（默认 ThreadStackSize = 2040 KB），`--release 8` 编译。

```
中序 [10020, 10050, 10080, 10100, 10150, 10200, 10300]  ← 从优到劣
显式栈，前 5 档：[10020, 10050, 10080, 10100, 10150]
n= 10,000 树高  10,000   递归 StackOverflowError   显式栈 输出 10,000 个
n= 20,000 树高  20,000   递归 OK                    （JIT 编译后栈帧变小，门槛不固定）
n= 50,000 树高  50,000   递归 StackOverflowError   显式栈 输出 50,000 个
fib(40) = 102334155：调用 331,160,281 次，641 ms
```

## 练习

先猜结果，再运行。

1. 反向中序列出买盘（从高到低），递归和显式栈各一版。
2. 写 `total(Node)` 求子树挂单总量，再写“价格 ≤ x 的挂单总量”并剪枝。
3. 把 `height` 改成层序版本，在 10 万层的链上不溢出。
