# 回溯：把所有选择走一遍，走不通就退回来

对应页面：[回溯：把所有选择走一遍，走不通就退回来](https://study.ainiya.de/algo/backtracking.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.backtracking` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/backtracking compile exec:java -Dexec.mainClass=labs.algo.backtracking.<类名>`
- 类：`FillOrders`（整张吃单凑手数：不剪枝 / 剪枝 + 去重的节点数，存引用和忘撤销两个坑）、`RegexBacktrack`（`(a+)+b` 和带反向引用的 `(x?)(a+)+b\1` 匹配耗时）。

## 预期输出

2026-11-21，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

```
挂单 [2, 3, 5, 6] 凑 8：[[2, 6], [3, 5]]，不剪枝走了 16 个节点
                 剪枝后 [[2, 6], [3, 5]]，走了 9 个节点
坑 1 res.add(path)：[[], []]
n=24：不剪枝 16777216 节点 169 ms，156 种；剪枝+去重 1325 节点 0 ms，93 种
(a+)+b         n=26  false       0.1 ms
(x?)(a+)+b\1   n=26  false    2505.4 ms
```

## 练习

先猜结果，再运行。

1. 用同一个模板枚举均线参数（短 × 长 × 止损），在选长周期那层剪掉“长 ≤ 短”，应输出 30 种。
2. 可以重复吃（递归传 `i`）：[2,3,5,6] 凑 8 应有 4 种。
3. 给 `pruned` 加节点上限，超过 100 万抛异常。
