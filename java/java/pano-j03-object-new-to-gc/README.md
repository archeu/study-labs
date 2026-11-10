# 全景：一个对象从 new 到被回收

对应页面：[全景：一个对象从 new 到被回收](https://archeu.github.io/study-notes/java/pano-j03-object-new-to-gc.html)（Java 地图 J03）

| 类 | 看什么 |
|---|---|
| `Order` | 交易靶场的订单：对象头 12 字节 + 6 个字段，一共 48 字节 |
| `ObjLife` | 让订单在 G1 上走完一生：类加载、逃逸分析、分配与布局、Young GC、晋升、大对象、并发标记与 Mixed GC，最后故意泄漏到 OOM。参数 `noleak` 只走到第 7 站 |

## 怎么跑

JDK 21（全景页约定），`--release 21` 编译。堆固定 256 MB，GC 日志和类加载日志写到文件。

```bash
cd java
mvn -q -pl java/pano-j03-object-new-to-gc compile dependency:build-classpath -Dmdep.outputFile=/tmp/j03.cp
CP=java/pano-j03-object-new-to-gc/target/classes:$(cat /tmp/j03.cp)
mkdir -p /tmp/j03run && cd /tmp/j03run
java -Xms256m -Xmx256m -Xlog:gc*:file=gc.log -Xlog:class+load:file=cl.log \
     -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=oom.hprof \
     -cp $OLDPWD/$CP labs.java.pano_j03_object_new_to_gc.ObjLife
# 程序打印 “PAUSE pid=…” 后会停 4 秒，另开一个终端：jcmd <pid> GC.class_histogram | head
grep -E "Pause|Concurrent Mark Cycle" gc.log | less
rm -f oom.hprof        # 约 380 MB，看完就删

# 对照：关掉逃逸分析，只走到第 7 站
java -Xms256m -Xmx256m -XX:-DoEscapeAnalysis -cp $OLDPWD/$CP labs.java.pano_j03_object_new_to_gc.ObjLife noleak
# 第 10 站：512 MB 的机器（容器）上默认堆多大
java -XX:MaxRAM=512m -XX:+PrintFlagsFinal -version | grep -E " MaxHeapSize| MaxRAMPercentage| ThreadStackSize"
```

## 预期输出

2 核 ARM，JDK 21.0.12：

```
① Order 由谁加载：app；String 由谁加载：null（null = 启动类加载器）
③ … Instance size: 48 bytes
② 循环里 new 了 5000 万个临时 Money：这个线程实际在堆上分配了 0.7 MB      （关掉逃逸分析：1144.4 MB）
⑤ new 了 2000 万个短命订单（约 915 MB）：G1 Young Generation 8 次共 13 ms；
⑥ 把 100 万个订单放进缓存，再过几次 Young GC：G1 Young Generation 4 次共 117 ms；   老年代：2 MB → 127 MB
④ 分配了 40 个 2 MB 的 byte[] …
⑦ 清空缓存后继续下单：G1 Young Generation 12 次共 12 ms；G1 Concurrent GC 4 次共 3 ms；   老年代：199 MB → 4 MB
⑧ 开始泄漏 …
⑨ java.lang.OutOfMemoryError: Java heap space，这时 LEAK 里有 4994267 个订单：… G1 Old Generation 7 次共 857 ms；
```

GC 日志里能找到：`Pause Young (Normal) (G1 Evacuation Pause) 154M->2M(256M) 0.586ms`、`(Evacuation Failure)`、`G1 Humongous Allocation`、`Concurrent Mark Cycle 209.813ms`、`Pause Young (Mixed)`、`Pause Full (G1 Compaction Pause) 255M->255M(256M)`。

- 第 7 站的“老年代降到多少”每次不一样（补跑那次只降到 113 MB）：并发标记和 Mixed GC 什么时候轮到由 G1 决定。
- 直方图第一行：`2501025  120049200  …Order`，正好每个 48 字节。

## 练习

先猜结果，再运行。

1. 把 `-Xmx` 改成 1g，OOM 还会来吗？Full GC 的停顿变长还是变短？
2. 把第 4 站的 2 MB 改成 400 KB，GC 日志里什么消失了？
3. 把 LEAK 换成 `WeakHashMap` 或给它加上限，Full GC 还出现吗？
