# synchronized 与锁升级、CAS 与原子类

对应页面：[synchronized 与锁升级、CAS 与原子类](https://archeu.github.io/study-notes/juc/synchronized-cas-atomic.html)

`--release 8`，依赖 `org.openjdk.jol:jol-core:0.17`（打印对象头）。

| 类 | 做什么 |
|---|---|
| `MarkWord` | 库存对象在 7 种情况下的 mark word：新建、没人抢时加锁、放锁、两个线程抢、都走了、算过哈希、再加锁 |
| `SyncBytecode` | 给 `javap -c -v` 看：同步块的 monitorenter / monitorexit 和异常表，同步方法的 ACC_SYNCHRONIZED |
| `Aba` | 订单状态 PAID → REFUNDING → PAID：AtomicReference 看不出来，AtomicStampedReference 拒绝 |
| `Bench` | 同一个计数，synchronized / ReentrantLock / AtomicInteger / 手写 CAS 循环 / LongAdder，1、2、4 个线程 |

## 怎么跑

```bash
cd java
J=/usr/lib/jvm/java-17-openjdk-arm64
JAVA_HOME=$J mvn -q -pl juc/synchronized-cas-atomic compile
mvn -q -pl juc/synchronized-cas-atomic dependency:build-classpath -Dmdep.outputFile=/tmp/sca.cp
CP=juc/synchronized-cas-atomic/target/classes:$(cat /tmp/sca.cp)

$J/bin/java -cp $CP labs.juc.synchronized_cas_atomic.MarkWord
$J/bin/java -XX:+UseBiasedLocking -XX:BiasedLockingStartupDelay=0 -cp $CP labs.juc.synchronized_cas_atomic.MarkWord
$J/bin/javap -c -v -p -cp juc/synchronized-cas-atomic/target/classes labs.juc.synchronized_cas_atomic.SyncBytecode
$J/bin/java -cp $CP labs.juc.synchronized_cas_atomic.Aba
$J/bin/java -XX:-EliminateLocks -cp $CP labs.juc.synchronized_cas_atomic.Bench   # 关掉锁粗化 / 消除，测真实的加锁开销
```

## 预期输出

本机 2 核 ARM（Neoverse-N1，UseLSE=true），JDK 17.0.20：

```
1. 刚 new 出来                         0x0000000000000001 (non-biasable; age: 0)
2. main 线程拿着锁，没人抢             0x0000fe80e872e1e0 (thin lock: 0x0000fe80e872e1e0)
3. 放开锁以后                          0x0000000000000001 (non-biasable; age: 0)
4. exec-17 拿着锁，exec-23 在门口等    0x0000fe8098001502 (fat lock: 0x0000fe8098001502)
5. 两个都走了                          0x0000fe8098001502 (fat lock: 0x0000fe8098001502)
6. 新对象，算过 identityHashCode       0x0000004b4523f801 (hash: 0x4b4523f8; age: 0)
7. 再对它加锁                          0x0000fe80e872e1e0 (thin lock: 0x0000fe80e872e1e0)
```

打开偏向锁：第 1 步 `biasable`，第 2、3 步 `biased`（放锁后还偏向 main），第 4、5 步 fat lock，第 7 步 thin lock（算过哈希就不能偏向）。

`Bench`，单位百万次/秒，每格 2000 万次、3 轮取最快：

```
                          -XX:-EliminateLocks            默认参数（允许锁粗化）
写法                       1 线程  2 线程  4 线程          1 线程  2 线程  4 线程
synchronized                59.0    17.9    29.6           233.3    83.5   123.7
ReentrantLock               53.4    11.2    35.2            63.1    20.0    44.4
AtomicInteger.increment    148.4   123.5    75.5           149.2    79.4    81.1
手写 CAS 循环              110.1    23.8    29.9           112.2    39.5    21.1
LongAdder                   90.7   166.4   177.3            89.6   168.4   175.1
```

加 `-XX:+UseBiasedLocking`（同时 `-XX:-EliminateLocks`）：单线程 synchronized 321.8，2 / 4 线程 19.6 / 26.5。
普通 main 程序，不是 JMH，2 核上 2 线程和 4 线程的先后关系不稳定，只看量级。

## 练习

1. 在 `MarkWord` 第 4 步里让 exec-17 调 `stock.wait()`，再打印对象头和 exec-17 的状态。
2. 把 `Bench` 的手写 CAS 循环改成 `getAndUpdate(v -> v + 1)`，看吞吐和直接 `incrementAndGet` 比怎么样。
3. 去掉 `-XX:-EliminateLocks`，单线程 synchronized 为什么快了 4 倍？用 `-XX:-EliminateLocks` 和 `-XX:+EliminateLocks` 各跑一次对比。
4. 给 `Aba` 加一个场景：用数据库乐观锁（`UPDATE … SET status=?, version=version+1 WHERE id=? AND version=?`）的思路解释为什么它不怕 ABA。
