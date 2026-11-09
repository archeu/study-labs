# 线程池下一圈：怎么配、拒绝策略、动态调参与监控

对应页面：[线程池下一圈](https://archeu.github.io/study-notes/juc/threadpool-tuning.html)
上一圈：[ThreadPoolExecutor.execute 源码](https://archeu.github.io/study-notes/juc/threadpool-execute.html)

`--release 8`，只用 JDK 自带的类。

| 类 | 做什么 |
|---|---|
| `Overload` | 通知池（核心 2、最大 4、队列 10）一口气收 100 条 20 ms 的通知，四种拒绝策略各跑一次 |
| `LiveResize` | 核心 2、最大 2 压进 400 条，每 100 ms 打一行监控，第 500 ms 在线调到 16；最后演示 shutdown → awaitTermination → shutdownNow |

## 怎么跑

```bash
cd java
J=/usr/lib/jvm/java-17-openjdk-arm64
JAVA_HOME=$J mvn -q -pl juc/threadpool-tuning compile
CP=juc/threadpool-tuning/target/classes
$J/bin/java -cp $CP labs.juc.threadpool_tuning.Overload
$J/bin/java -cp $CP labs.juc.threadpool_tuning.LiveResize
```

## 预期输出

本机 2 核 ARM，JDK 17.0.20：

```
策略              发出  被拒/丢  提交 100 条用了  全部发完  丢掉的通知（举例）           谁执行了通知
AbortPolicy          14       86             9 ms       86 ms  [15, 16, 17, 18, 19, 20]…    [池里线程]
CallerRunsPolicy    100        0           363 ms      423 ms  -                            [main, 池里线程]
DiscardPolicy        14       86             0 ms       81 ms  [15, 16, 17, 18, 19, 20]…    [池里线程]
DiscardOldestPolicy   14       86             0 ms       80 ms  [3, 4, 5, 6, 7, 8]…          [池里线程]

先 setCorePoolSize(16)（大于当前最大 2）：抛 IllegalArgumentException
  时间ms  线程数  活跃  排队  已完成
      0       2     2   398       0
    523       2     2   346      52
   ↑ 第 523 ms：setMaximumPoolSize(16) 再 setCorePoolSize(16)，线程数马上变成 16
    625      16    16   258     126
    930      16    16    12     372
400 条全部发完：1030 ms（全程 2 个线程要约 4000 ms）

shutdown 之后再提交：RejectedExecutionException
等 500 ms：没做完，已做完 16 个，队列里还有 8 个
shutdownNow：退回没开始的 8 个，被中断的 16 个，做完的共 16 个
```

## 练习

1. 写一个“记录 + 补发”的拒绝策略，加进 `Overload`。
2. 用页面上的计算器算 LiveResize 的参数（每秒 400 条、20 ms、计算 0.1 ms、2 核、可等 500 ms），改掉开头的 2 / 2 / 1000 再跑。
3. 记录 shutdownNow 退回的和被中断的任务；把任务改成不响应中断，看 shutdownNow 之后多久才停。
