# JIT、逃逸分析与常见的性能误区

对应页面：[JIT、逃逸分析与常见的性能误区](https://archeu.github.io/study-notes/jvm/jvm-jit-escape.html)（J03 全景第 2 站）

| 类 | 做什么 |
|---|---|
| `Money` | 交易靶场的不可变金额：对象头 12 B + long 8 B = 24 B，每次运算 new 一个新的 |
| `Warmup` | 算价 `priceOf` 每批 20 万次，打印每批 ns/次和 B/次：看解释 → C1 → C2 的预热，以及 C2 把 3 个 `Money` 标量替换成 0 B |
| `Escape` | 8 种写法（只在方法里用、被返回、存进静态字段、传给单态 / 多态接口、两分支合流、小数组、大数组），各预热 300 万次再量 1000 万次的 B/次 |
| `Myths` | ① 手写微基准：结果没用上，循环被 C2 删掉；② 同一处反复 NPE，第几千次起堆栈变成 0 层 |
| `Alloc` | 用 `com.sun.management.ThreadMXBean` 读当前线程累计分配字节 |

## 怎么跑

`--release 8` 编译，用 JDK 17 运行。

```bash
cd java && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -q -pl jvm/jvm-jit-escape compile
cd jvm/jvm-jit-escape
J=/usr/lib/jvm/java-17-openjdk-arm64/bin/java; M=labs.jvm.jvm_jit_escape

$J -cp target/classes $M.Warmup
$J -Xint -Dbatches=5 -cp target/classes $M.Warmup
$J -XX:TieredStopAtLevel=1 -cp target/classes $M.Warmup
$J -XX:-DoEscapeAnalysis -cp target/classes $M.Warmup
$J -XX:+PrintCompilation -Dbatches=3 -cp target/classes $M.Warmup | grep -E "priceOf|batch|Money"
$J -cp target/classes $M.Escape
$J -XX:-DoEscapeAnalysis -cp target/classes $M.Escape
$J -cp target/classes $M.Myths
$J -XX:-OmitStackTraceInFastThrow -cp target/classes $M.Myths
```

## 预期输出（2 核 ARM，JDK 17.0.20.1）

```
# Warmup（稳态取第 3～30 批中位数）
默认分层编译        3.7 ns/次    0 B/次     （第 1 批 24.7 ns、6.1 B）
-XX:TieredStopAtLevel=1   17 ns/次   72 B/次
-XX:-DoEscapeAnalysis     23 ns/次   72 B/次
-Xint                    358 ns/次   72 B/次

# PrintCompilation
69   67       3   Warmup::priceOf (40 bytes)
69   70       4   Warmup::priceOf (40 bytes)
70   67       3   Warmup::priceOf (40 bytes)   made not entrant
71   73 %     3   Warmup::batch @ 5 (36 bytes)

# Escape          默认    -XX:-DoEscapeAnalysis
A 只在方法里用       0.0      48.0
B 被返回            24.0      24.0
C 存进静态字段       24.0      24.0
D 传参·1 种实现      0.0      24.0
E 传参·3 种实现     24.0      24.0
F 两分支 new 合流    24.0      24.0
G 小数组 long[4]     0.0       0.0
H 大数组 long[100] 816.0     816.0

# Myths
第 3 轮  结果丢掉    0.00 ms   结果用上   67.65 ms
第 1 次：2 层，message=Cannot read field "code" because "o.coupon" is null
第 6761 次起：0 层，message=null
（加 -XX:-OmitStackTraceInFastThrow：20 万次里堆栈一直都在）
```

## 练习

先猜结果，再运行。

1. 给 `Warmup` 加 `-XX:CompileCommand=quiet -XX:CompileCommand=dontinline,labs.jvm.jvm_jit_escape.Money::times`：稳态每次分配几字节？是哪几个 `Money` 进了堆？
2. 把 `Escape.THREE` 改成只有 `MockPay`、`AliPay`，下标改成 `i % 2`：E 那一行是 0 B 还是 24 B？再加回第三种，用 `PrintCompilation` 找 `passMega` 的 `made not entrant`。
3. 把 `Myths.discard` 的结果异或进一个 `static volatile long` 字段：“结果丢掉”那一列变成多少？再用 JMH（`jmh-core` + `jmh-generator-annprocess`）重写一遍，比较 ns/次。
