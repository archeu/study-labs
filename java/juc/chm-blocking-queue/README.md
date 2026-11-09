# ConcurrentHashMap 与阻塞队列

对应页面：[ConcurrentHashMap 与阻塞队列](https://archeu.github.io/study-notes/juc/chm-blocking-queue.html)

`--release 8`，只用 JDK 自带的类。

| 类 | 做什么 |
|---|---|
| `MapRace` | 两个线程各 put 50 万个用户：HashMap / synchronizedMap / ConcurrentHashMap；销量计数 get 再 put vs merge |
| `ChmPeek` | 反射看内部：扩容时 table 长度和 sizeCtl、同一个桶挤满后变 TreeBin、baseCount 和 counterCells、computeIfAbsent 递归 |
| `QueueDemo` | ArrayBlockingQueue 满了 offer 超时、DelayQueue 到期才取、2 生产 2 消费 ArrayBlockingQueue vs LinkedBlockingQueue |

## 怎么跑

```bash
cd java
J=/usr/lib/jvm/java-17-openjdk-arm64
JAVA_HOME=$J mvn -q -pl juc/chm-blocking-queue compile
CP=juc/chm-blocking-queue/target/classes
$J/bin/java -cp $CP labs.juc.chm_blocking_queue.MapRace
$J/bin/java --add-opens java.base/java.util.concurrent=ALL-UNNAMED -cp $CP labs.juc.chm_blocking_queue.ChmPeek
$J/bin/java -cp $CP labs.juc.chm_blocking_queue.QueueDemo
```

## 预期输出

本机 2 核 ARM，JDK 17.0.20：

```
== 1. 两个线程各 put 50 万个不同的 key，应有 1000000 ==
第 1 次  HashMap 962723，218 ms   synchronizedMap 1000000，241 ms   ConcurrentHashMap 1000000，235 ms
第 2 次  HashMap 839013，147 ms   synchronizedMap 1000000，228 ms   ConcurrentHashMap 1000000，87 ms
第 3 次  HashMap 871315，172 ms   synchronizedMap 1000000，234 ms   ConcurrentHashMap 1000000，144 ms
== 2. 销量计数：两个线程各 +1 共 100 万次，应有 2000000 ==
第 1 次  get 再 put：1970799    merge：2000000
第 2 次  get 再 put：1954211    merge：2000000

放了   1 个：  table 长 16，sizeCtl=12
放了  12 个：  table 长 32，sizeCtl=24
同一个桶里 8 个：表长 128，桶 88 的头节点是 Node      （new ConcurrentHashMap(64) 建出 128）
同一个桶里 9 个：表长 128，桶 88 的头节点是 TreeBin
size()=600000，baseCount=40954，counterCells 2 格，格子里合计 559046
抛 IllegalStateException: Recursive update

队列满了，offer(10004, 200ms) = false，等了 200 ms
第 1 个 take：订单 10018，在第 100 ms
第 2 个 take：订单 10019，在第 200 ms
第 3 个 take：订单 10017，在第 300 ms
第 1 轮  ArrayBlockingQueue 304 ms   LinkedBlockingQueue 650 ms
第 2 轮  ArrayBlockingQueue 385 ms   LinkedBlockingQueue 576 ms
```

HashMap 丢多少每次不同；普通 main 程序，耗时只看方向。

## 练习

1. `MapRace` 第 2 段加 `compute` 和“值是 LongAdder”两种写法。
2. 一个线程不停 put 触发扩容，另一个反射扫旧表，数 hash = −1 的 ForwardingNode。
3. 消费者变慢后，有界队列 + offer 超时 vs 无界 LinkedBlockingQueue，看降级个数和堆内存。
