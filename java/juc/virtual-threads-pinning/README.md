# 虚拟线程：什么时候用、pinning 的坑

对应页面：[虚拟线程下一圈：什么时候用、pinning 的坑](https://archeu.github.io/study-notes/juc/virtual-threads-pinning.html)

| 类 | 看什么 |
|---|---|
| `Throughput` | 平台线程池（200）vs 每任务一个虚拟线程。`io`：1 万个等 100 ms 的任务；`cpu`：200 个固定计算量的任务（加第二个参数 `vt-first` 换先后顺序）。打印耗时和真正干活的操作系统线程数 |
| `Pinning` | 100 个虚拟线程各拿一把自己的锁，在锁里等 100 ms。`sync`：synchronized，JDK 21 上被钉住；`lock`：ReentrantLock，正常卸载 |

## 怎么跑

`--release 21` 编译，必须用 JDK 21 运行（虚拟线程在 8、17 上没有）。

```bash
cd java
mvn -q -pl juc/virtual-threads-pinning compile          # 系统默认 java 是 21
CP=juc/virtual-threads-pinning/target/classes
P=labs.juc.virtual_threads_pinning
java -cp $CP $P.Throughput io
java -cp $CP $P.Throughput cpu
java -cp $CP $P.Throughput cpu vt-first
for m in sync lock; do java -Djdk.virtualThreadScheduler.parallelism=2 -cp $CP $P.Pinning $m; done
java -Djdk.virtualThreadScheduler.parallelism=2 -Djdk.tracePinnedThreads=short -cp $CP $P.Pinning sync
java -XX:StartFlightRecording=filename=pin.jfr -Djdk.virtualThreadScheduler.parallelism=2 -cp $CP $P.Pinning sync
jfr summary pin.jfr | grep VirtualThreadPinned && rm pin.jfr
```

## 预期输出

2 核 ARM，JDK 21.0.12：

```
$ Throughput io
  平台线程池 200        5037 ms，真正干活的操作系统线程 200 个
  虚拟线程 每任务一个    265 ms，真正干活的操作系统线程 2 个

$ Throughput cpu / cpu vt-first
  先跑的那组慢（JIT 热身）；后跑的那组不管哪种都在 2.1～2.2 秒 = 4 秒计算量 ÷ 2 核

$ Pinning sync / lock
模式 sync ：100 个虚拟线程、各等 100 ms，用了 5030 ms（载体线程 2 个）
模式 lock ：100 个虚拟线程、各等 100 ms，用了 125 ms（载体线程 2 个）

$ -Djdk.tracePinnedThreads=short
VirtualThread[#20]/runnable@ForkJoinPool-1-worker-2 reason:MONITOR
    labs.juc.virtual_threads_pinning.Pinning.lambda$main$0(Pinning.java:23) <== monitors:1

$ jfr summary
 jdk.VirtualThreadPinned                   100          1400
```

- JDK 24 起（JEP 491）synchronized 不再钉住，`sync` 应接近 `lock`，`jdk.tracePinnedThreads` 也被去掉了。

## 练习

先猜结果，再运行。

1. 给 `Throughput` 加第三种：`Executors.newFixedThreadPool(200, Thread.ofVirtual().factory())`，是 5 秒还是 0.25 秒？
2. 在 io 任务里加 `Semaphore(50)`，预测 1 万个任务要多久。
3. 把 `Pinning` 的 sleep 换成 `lock.wait(100)`（仍在 synchronized 里），对比耗时，用 JFR 看载体线程有没有变多。
