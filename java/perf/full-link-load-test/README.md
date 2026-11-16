# 全链路压测与容量规划

对应页面：[全链路压测与容量规划](https://study.ainiya.de/perf/full-link-load-test.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.perf.full_link_load_test` 包下的 main。
- 命令行：`cd java && mvn -q -pl perf/full-link-load-test compile exec:java -Dexec.mainClass=labs.perf.full_link_load_test.<类名>`

## 预期输出

2 核 ARM 虚拟机，JDK 17（压测端和服务在同一台机器上，只看形状）。模块按 `--release 11` 编译，因为用了 `java.net.http.HttpClient`。

```
CapacityLab（开环阶梯，8 个工作线程，每请求 0.3 ms CPU + 4 ms 等待）
目标QPS  实际QPS   P50(ms)  P99(ms)  超过50ms
   400      400       6.0      10.7     0.0%
   800      799       6.5      21.3     0.0%
  1200     1199       6.9     106.1     5.8%
  1500     1482      15.6     160.3    35.1%
  1700     1588     166.4     373.3    91.4%
  1900     1536     565.0    1178.5    97.3%
  2200     1457    1709.9    2577.5    97.1%

ShadowTagLab
同步写（同一个线程）                 影子表 100 行，真表   0 行
线程池里写，ThreadLocal              影子表   0 行，真表 100 行
线程池里写，InheritableThreadLocal   影子表   0 行，真表 100 行
线程池里写，提交时包装任务           影子表 100 行，真表   0 行
```

## 练习

先猜结果，再运行。

1. 把 `CapacityLab` 改成 64 个线程闭环（发一个等一个），对比 P99 和 QPS。
2. 工作线程 8 → 16，拐点右移多少？新瓶颈是什么？
3. `ShadowTagLab` 加一种 `CompletableFuture.runAsync` 写法，先预测影子表几行。
4. 去掉入口的 `PT.remove()`，压测请求和真实请求交替走线程池，数“传染”了几行。
5. 给 `CapacityLab` 加“P99 目标”参数，自动找出单台安全容量。
