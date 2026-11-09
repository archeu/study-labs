# 线程、线程状态与上下文切换

对应页面：[线程、线程状态与上下文切换](https://archeu.github.io/study-notes/juc/thread-state-context-switch.html)

两个程序，`--release 8`（Java 8 写法，只用 JDK 自带的类）。读 `/proc`，所以只能在 Linux 上跑。

| 类 | 做什么 |
|---|---|
| `States` | 交易靶场里几个线程各停在一种状态上，同时打印 Java 的 `getState()` 和内核 `/proc` 里的状态；带 `hold` 参数时停 60 秒，给你拿 `jcmd Thread.print` |
| `CtxSwitch` | 四个实验：同样多的计算分给 2 / 8 / 64 / 512 个线程；“算 1 ms + 等 20 ms”的任务开不同线程数；两个线程 park / unpark 来回交接；新建线程 vs 线程池 |
| `Proc` | 读 `/proc/self/task`、`/proc/thread-self/status` 的小工具 |

## 怎么跑

```bash
cd java
J=/usr/lib/jvm/java-17-openjdk-arm64
JAVA_HOME=$J mvn -q -pl juc/thread-state-context-switch compile
CP=juc/thread-state-context-switch/target/classes
$J/bin/java -cp $CP labs.juc.thread_state_context_switch.States
$J/bin/java -cp $CP labs.juc.thread_state_context_switch.States hold &   # 另开终端：jcmd <pid> Thread.print
$J/bin/java -cp $CP labs.juc.thread_state_context_switch.CtxSwitch
```

## 预期输出

本机 2 核 ARM、JDK 17.0.20、Linux 6.8：

```
线程名             Java 状态        内核状态  内核线程号
order-new          NEW              -        （内核里没有）
order-done         TERMINATED       -        （内核里没有）
price-calc         RUNNABLE         R        931005
acceptor           RUNNABLE         S        931042
exec-17            TIMED_WAITING    S        931043
exec-23            BLOCKED          S        931047
pay-waiter         WAITING          S        931048
order-canceller    WAITING          S        931049
这个进程在内核里一共有 25 个线程（含 JVM 自己的 GC、JIT 等）
```

`CtxSwitch` 两次运行：

```
== 1. 纯计算：总量相同，分给不同个数的线程 ==      （第二次）
线程数   耗时ms   主动让出   被抢占                  耗时ms  主动让出  被抢占
     2     1025          0       333                  1039        2      319
     8     1028          9      1180                  1119       10     1564
    64     1160         47      1455                  1098       38     1359
   512     1393         21      1842                  1399       17     1897

== 2. 200 个任务，每个算 1 ms、等 20 ms ==
线程数   耗时ms   理论下限ms                          （第二次）
     2     2113       2100                            2120
     8      532        525                             533
    32      153        147                             152
   128      150        120                             149

== 3. 两个线程来回交接（park / unpark）==
平均一次 3.9 微秒（第二次 3.4）

== 4. 新建线程 vs 线程池 ==
新建、启动、等它结束一个线程：82.0 微秒（第二次 93.5）
交给已有线程：8.8 微秒（第二次 9.1）
```

## 练习

先猜结果，再运行。

1. 带 `hold` 跑 `States`，用 `top -H -p <pid>` 找到 CPU 最高的线程号，转成十六进制，在 `jcmd <pid> Thread.print` 里按 `nid` 找它。
2. 把 exec-17 锁里的 `sleep` 换成 `stockLock.wait(60_000)`，exec-23 变成什么状态？
3. 把 `order-canceller` 改成循环 `take()`，主线程 1 秒后 `interrupt()` 它；先写一个吞掉异常的版本，看它停不下来，再改好。
4. 把实验 2 的“1 ms / 20 ms”改成你项目里一个接口的真实比例，先用公式 `核数 × (1 + 等待/计算)` 估线程数，再跑出拐点。
5. `taskset -c 0` 只给一个核再跑 `CtxSwitch`，实验 1、3 的数字怎么变？
