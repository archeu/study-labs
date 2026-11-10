# CompletableFuture 与异步编排

对应页面：[CompletableFuture 与异步编排](https://archeu.github.io/study-notes/juc/completablefuture-orchestration.html)

| 类 | 看什么 |
|---|---|
| `WhichThread` | 同一个回调在哪个线程跑：默认池、已完成 / 未完成时的 thenApply、Async、自己的池、orTimeout 超时后。参数 `block`：8 个阻塞 200 ms 的任务丢进默认池 |
| `Notify` | 全景第 10 站发三条通知：串行 vs allOf 并行、异常被吞和 exceptionally、completeOnTimeout 兜底但任务还在跑、ThreadLocal 带不过去和 withUser 包装 |

## 怎么跑

用 `--release 11` 编译（`orTimeout` / `completeOnTimeout` 是 JDK 9 加的，JDK 8 上没有，替代写法见页面⑥第三圈），JDK 17 运行。

```bash
cd java
J=/usr/lib/jvm/java-17-openjdk-arm64
JAVA_HOME=$J mvn -q -pl juc/completablefuture-orchestration compile
CP=juc/completablefuture-orchestration/target/classes
P=labs.juc.completablefuture_orchestration
$J/bin/java -cp $CP $P.WhichThread
$J/bin/java -Djava.util.concurrent.ForkJoinPool.common.parallelism=4 -cp $CP $P.WhichThread
for n in 1 2 4; do $J/bin/java -Djava.util.concurrent.ForkJoinPool.common.parallelism=$n -cp $CP $P.WhichThread block; done
$J/bin/java -cp $CP $P.Notify
```

## 预期输出

2 核 ARM，JDK 17.0.20：

```
$ WhichThread
CPU 核数 2，commonPool 并行度 1
1 supplyAsync 默认池            → Thread-0
2 thenApply，前一步还没完成      → Thread-1（完成前一步的线程）
3 thenApply，前一步已经完成      → main（调用 thenApply 的线程）
4 thenApplyAsync                 → Thread-2
5 thenApplyAsync(自己的池)       → notify-1
6 orTimeout 超时后的 exceptionally → CompletableFutureDelayScheduler（TimeoutException）

$ WhichThread block（并行度 1 / 2 / 4）
204 ms，8 个线程 / 806 ms，2 个线程 / 405 ms，4 个线程

$ Notify
1 串行：603 ms
2 并行 allOf：320 ms
3 runAsync 里抛了异常，没人 join：什么都没打印；exceptionally 拿到 CompletionException
4 completeOnTimeout：第 503 ms 拿到兜底值；卡住的任务第 1501 ms 才结束
5 异步线程里 USER=null；包一层 withUser 后 USER=1024；用完后同一个池里 USER=null
```

- 2 核机器上 commonPool 并行度是 1，CompletableFuture 改用“每个任务 new 一个线程”，所以线程名是 Thread-N。

## 练习

先猜结果，再运行。

1. 加一个 100 ms 的 `queryPhone`，用 thenCompose 接上短信，和另外两条一起 allOf，先算总耗时。
2. 让超时后的短信任务真的停下来（可中断的 sleep + 对执行线程发中断）。
3. 写一个 Executor 包装器，在 `execute(r)` 时抓取 USER，去掉 withUser，看场景 5 还能不能拿到 1024。
