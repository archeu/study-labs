# new 一个对象的全过程：分配、对象头与内存布局

对应页面：[new 一个对象的全过程：分配、对象头与内存布局](https://archeu.github.io/study-notes/jvm/jvm-object-new-layout.html)（J03 全景第 3 站，下一圈）

| 类 | 做什么 |
|---|---|
| `Order` | 交易靶场的订单，和 J03 全景页同一个类 |
| `OrderLite` | 瘦身版订单（↺ 练习 1 的参考答案） |
| `Layout` | 用 JOL 打印 Order、Object、Long、int[3]、OrderLite 的布局，算哈希前后的 mark word，以及一个订单连同 String 的总大小 |
| `AllocCost` | new 100 万个 Order 看线程分配了多少字节；再让 N 个线程各 new 5000 万个，对比开 / 关 TLAB 的耗时 |

## 怎么跑

`--release 8` 编译，用 JDK 17 运行（21 上的布局一样）。

```bash
cd java && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -q -pl jvm/jvm-object-new-layout compile dependency:build-classpath -Dmdep.outputFile=cp.txt
cd jvm/jvm-object-new-layout
J=/usr/lib/jvm/java-17-openjdk-arm64/bin/java
$J -cp target/classes:$(cat cp.txt) labs.jvm.jvm_object_new_layout.Layout
$J -XX:-UseCompressedOops -cp target/classes:$(cat cp.txt) labs.jvm.jvm_object_new_layout.Layout
$J -Xms1g -Xmx1g -cp target/classes labs.jvm.jvm_object_new_layout.AllocCost 1
$J -Xms1g -Xmx1g -XX:-UseTLAB -cp target/classes labs.jvm.jvm_object_new_layout.AllocCost 1
```

## 预期输出（2 核 ARM，JDK 17.0.20）

- `Layout`：Order `Instance size: 48 bytes`，`skuId` 在偏移 12；算过 `identityHashCode` 后 mark word 里出现 `hash: 0x…`；Long 24、int[3] 32、OrderLite 40；一个 Order 连同 String 和 byte[] 共 96 字节。
- `-XX:-UseCompressedOops`：Order 56 字节，`status` 挪到偏移 48，对象头仍是 12 字节；总大小 112。
- `AllocCost 1`：`线程分配了 48,000,048 字节，平均每个 48.0 字节`；第 3 轮约 290 ms。
- `-XX:-UseTLAB`：第 3 轮约 5600 ms。参数换成 2 个线程：开 TLAB 约 310 ms，关 TLAB 约 11000 ms（总吞吐不增加）。

## 练习

先猜结果，再运行。

1. 按页面 ④-4 的规则，先在纸上排一遍 `OrderLite`，再跑 `Layout` 核对。（40 字节，不是 32）
2. 把 100 万个订单放进 `HashMap<Long, Order>`，status 用 `new String("PAID")`，用 `GraphLayout.parseInstance(map).totalSize()` 量；再换成字面量 `"PAID"` 比一比。
3. 给 `AllocCost` 加 `-Xlog:gc+tlab=debug`，找每个线程的 TLAB 大小、refills 和 waste。
