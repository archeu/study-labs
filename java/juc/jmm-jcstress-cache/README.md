# JMM 下一圈：jcstress、CPU 缓存与伪共享

对应页面：[JMM 下一圈：jcstress、CPU 缓存与伪共享](https://archeu.github.io/study-notes/juc/jmm-jcstress-cache.html)
上一圈：[Java 内存模型](https://archeu.github.io/study-notes/juc/java-memory-model.html)

| 类 | 做什么 |
|---|---|
| `StockTests` | 全景第 4、5 站：库存只剩 1 件，两个线程同时扣。`Plain`（普通 int）、`Volatile`、`Cas` 三种写法 |
| `ReorderTests` | 上一圈 ↺ 的预期：`SbPlain / SbOneVolatile / SbBothVolatile`、`MpPlain / MpVolatile`、`VolatileIncrement` |
| `FalseSharing` | 普通 main：两个线程各写自己的 volatile long 2 亿次，相邻 vs 中间塞 7 个 long |

`--release 8`，依赖 `org.openjdk.jcstress:jcstress-core:0.16`（编译时注解处理器生成 `META-INF/TestList`）。

## 怎么跑

```bash
cd java
J=/usr/lib/jvm/java-17-openjdk-arm64
JAVA_HOME=$J mvn -q -pl juc/jmm-jcstress-cache compile
mvn -q -pl juc/jmm-jcstress-cache dependency:build-classpath -Dmdep.outputFile=/tmp/jcs.cp
CP=juc/jmm-jcstress-cache/target/classes:$(cat /tmp/jcs.cp)

# 在一个空目录里跑（会生成 results/ 和 jcstress-results-*.bin.gz）
mkdir -p /tmp/jcs && cd /tmp/jcs
$J/bin/java -cp $OLDPWD/$CP org.openjdk.jcstress.Main -m quick            # 全部 9 个，2 核约 6.5 分钟
$J/bin/java -cp $OLDPWD/$CP org.openjdk.jcstress.Main -m quick -v -t Cas  # 只跑一个，-v 把没出“值得注意”结果的也打出来
cd -

$J/bin/java -cp juc/jmm-jcstress-cache/target/classes labs.juc.jmm_jcstress_cache.FalseSharing
```

## 预期输出

本机 2 核 ARM（Neoverse-N1，带 LSE 原子指令），JDK 17.0.20，jcstress 0.16，`-m quick`，所有配置合计：

| 测试 | 值得注意 / 禁止的结果 | 次数 | 占比 |
|---|---|---:|---:|
| `StockTests.Plain` | 1, 1 超卖 | 86,277,350 | 14.80% |
| `StockTests.Volatile` | 1, 1 超卖 | 94,029,807 | 33.59% |
| `StockTests.Cas` | 1, 1 禁止 | 0 | 0 |
| `SbPlain` | 0, 0 | 30,826,397 | 6.42% |
| `SbOneVolatile` | 0, 0 | 35,501,580 | 10.75% |
| `SbBothVolatile` | 0, 0 禁止 | 0 | 0 |
| `MpPlain` | 1, 0 看到标志没看到数据 | 368,515 | 0.07% |
| `MpVolatile` | 1, 0 禁止 | 0 | 0 |
| `VolatileIncrement` | 1 丢了一次 | 780,414 | 0.24% |

比例取决于两个线程碰得多齐，每次跑都不一样；要看的是“出现没出现”。
`MpPlain` 的 1, 0 在 x86 硬件上不应出现（TSO 不对调两次写、两次读），这一条没在 x86 上跑过。

`FalseSharing` 两次运行（各去掉第一轮预热）：

```
第 1 轮：挤在一行 316 ms，隔开 144 ms
第 2 轮：挤在一行 188 ms，隔开 63 ms
第 1 轮：挤在一行 311 ms，隔开 143 ms
第 2 轮：挤在一行 174 ms，隔开 139 ms
```

字段偏移（Unsafe 查的）：相邻时 a@16 b@24；隔开时 a@16 b@80。波动大，不是 JMH，只看方向。

## 练习

1. 加 `StockTests.Sync`：两个 Actor 都在 `synchronized (this)` 里扣，`"1, 1"` 声明为 FORBIDDEN。
2. 写一个“半发布”测试：写方 `order = new Order(); order.status = PAID;`，读方读 `order` 再读 `status`；再把 `order` 改成 volatile。
3. 在 `FalseSharing` 里加 `AtomicLong` vs `LongAdder` 两个线程同时累加的对比。
4. `taskset -c 0` 只给一个核跑 `FalseSharing`，差距还在吗？
