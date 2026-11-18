# 单调栈和单调队列：过去 60 秒最高价，每笔 O(1)

对应页面：[单调栈和单调队列：过去 60 秒最高价，每笔 O(1)](https://study.ainiya.de/algo/monotonic-stack-queue.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.monotonic_stack_queue` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/monotonic-stack-queue compile exec:java -Dexec.mainClass=labs.algo.monotonic_stack_queue.<类名>`
- 类：`WindowMax`（单调队列，过去 N 秒最高价）、`NextHigher`（单调栈，下一个更高价）、`Pitfalls`（坑 1～3）、`WindowBench`（四种做法测速）。
- `WindowBench` 别在 Maven 进程里跑：`java -Xms1g -Xmx1g -cp algo/monotonic-stack-queue/target/classes labs.algo.monotonic_stack_queue.WindowBench`，十来秒。

## 预期输出

2026-11-18，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

`WindowMax`（窗口 3 秒，和页面 ④ 的动画对得上）：

```
第5s 成交 100  队列 [102@3s 100@5s]              过去 3 秒最高 102
第8s 成交 104  队列 [104@8s]                     过去 3 秒最高 104
第12s 没有新成交  过去 3 秒最高 -1（-1 = 窗口里没有成交）
```

`WindowBench` 第 3 轮（300 秒行情、每秒 1 万笔、窗口 60 秒）：

```
单调队列（long 数组环）      每笔     14.2 ns   名单最长 2051
TreeMap<价格, 笔数>         每笔    357.6 ns   名单最长 83315
PriorityQueue + 延迟删除    每笔    127.6 ns   名单最长 2983727
每笔重扫整个窗口            每笔 826209.7 ns
```

## 练习

先猜结果，再运行。

1. 在 `WindowMax` 旁边加一个价格递增的队列，输出振幅；第 8 秒应该是 104 − 97 = 7。
2. 把 `NextHigher` 改成股票价格跨度：101 103 102 99 100 98 97 104 → 1 2 1 1 2 1 1 8。
3. 给 `WindowMax` 前面加 1 秒缓冲按时间排序，让 `Pitfalls` 坑 3 答出 105。
