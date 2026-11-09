# AQS 下一圈：读写锁、StampedLock、Semaphore 与 JFR 找锁竞争

对应页面：[AQS 下一圈](https://archeu.github.io/study-notes/juc/aqs-rwlock-jfr.html)
上一圈：[AQS 源码](https://archeu.github.io/study-notes/juc/aqs.html)

`--release 8`，只用 JDK 自带的类。

| 类 | 做什么 |
|---|---|
| `RwBench` | 库存查询读 95% / 扣减写 5%，4 个线程，ReentrantLock、读写锁（非公平 / 公平）、StampedLock 乐观读各跑 2 秒；参数是读的工作量（默认 40 轮） |
| `SlowLock` | 锁里一次 30 ms 的“慢 SQL”：synchronized 和 ReentrantLock 各 8 线程 × 10 单；再用 Semaphore(3) 给“库存服务”做舱壁。配 JFR 跑 |

## 怎么跑

```bash
cd java
J=/usr/lib/jvm/java-17-openjdk-arm64
JAVA_HOME=$J mvn -q -pl juc/aqs-rwlock-jfr compile
CP=juc/aqs-rwlock-jfr/target/classes

$J/bin/java -cp $CP labs.juc.aqs_rwlock_jfr.RwBench          # 读很短
$J/bin/java -cp $CP labs.juc.aqs_rwlock_jfr.RwBench 2000     # 读很长

$J/bin/java -XX:StartFlightRecording:filename=/tmp/slow.jfr,settings=profile -cp $CP labs.juc.aqs_rwlock_jfr.SlowLock
$J/bin/jfr summary /tmp/slow.jfr
$J/bin/jfr print --events jdk.JavaMonitorEnter /tmp/slow.jfr
$J/bin/jfr print --stack-depth 10 --events jdk.ThreadPark /tmp/slow.jfr   # 默认只打 5 帧，看不到业务方法
```

## 预期输出

本机 2 核 ARM，JDK 17.0.20。`RwBench`（读吞吐、写吞吐单位万次/秒）：

```
读一次算 40 轮                读        写    写等锁最久 ms     （第二次：读 / 写等最久）
ReentrantLock               1515      79.7        7.4             1503 / 6.9
ReentrantReadWriteLock      1090      57.3       10.6             1052 / 10.0
ReentrantReadWriteLock(公平)  20       1.1        2.0               24 / 3.3
StampedLock 乐观读           2293     120.7        6.0             2328 / 9.0
（乐观读失败、退回读锁约 184 万次）

读一次算 2000 轮
ReentrantLock                 42       2.2       10.4
ReentrantReadWriteLock        47       2.5       10.0
ReentrantReadWriteLock(公平)   17       0.9        2.4
StampedLock 乐观读             75       3.9        7.4
```

`SlowLock`：

```
synchronized 里慢 SQL：80 单用了 2413 ms（串行下限 2400 ms）
ReentrantLock 里慢 SQL：80 单用了 2409 ms
Semaphore(3) 限并发：最多同时 3 个在调库存服务，等 100 ms 拿不到许可被拒绝 17 次，用了 696 ms
```

JFR 汇总（`jfr print --json --stack-depth 20` 后按事件、锁类、第一个业务方法分组）：

```
jdk.ThreadPark        ReentrantLock$NonfairSync  useCoupon     74 条  合计 8899 ms  最长 1653 ms
jdk.JavaMonitorEnter  java.lang.Object           deductStock    7 条  合计 8414 ms  最长 2107 ms
jdk.ThreadPark        Semaphore$NonfairSync      舱壁           35 条  合计 1932 ms  最长 100 ms
```

## 练习

1. 给 `RwBench` 加第五种：不可变库存快照 + volatile 引用，写时加锁替换、读不加锁。
2. 把 `SlowLock.deductStock` 的慢 SQL 挪到锁外，重新录 JFR，对比锁事件。
3. 故意把 Semaphore 写成“没拿到也 release”，看最多同时几个。
4. 用 `jcmd <pid> JFR.start` / `JFR.dump` 给一个已经在跑的进程录一段。
