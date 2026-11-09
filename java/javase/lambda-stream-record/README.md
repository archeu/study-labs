# Lambda、Stream、record、模式匹配：编译后变成了什么

对应页面：[Lambda、Stream、record、模式匹配](https://archeu.github.io/study-notes/javase/lambda-stream-record.html)
（全景：一个订单对象的一生 · 第 2、5、6 站；J01 新特性的下一圈）

- `Demo`（JDK 17）：Lambda 的隐藏类和捕获、Stream 的执行顺序和短路、parallelStream 的线程、装箱代价、record 生成了什么。
- `src/jdk21/PatternSwitch.java`（JDK 21）：sealed + switch 模式匹配、record 模式、when 守卫、null。17 上是预览，编译不过，所以单独放、单独编译。

## 怎么跑

```bash
cd java
mvn -q -pl javase/lambda-stream-record compile
C=javase/lambda-stream-record/target/classes
java -cp $C labs.javase.lambda_stream_record.Demo                         # JDK 17
javap -c -p -cp $C labs.javase.lambda_stream_record.Demo | grep invokedynamic
javap -v -p -cp $C 'labs.javase.lambda_stream_record.Demo$OrderLine' | grep ObjectMethods

# 模式匹配：用 JDK 21
javac --release 21 -d /tmp/ps javase/lambda-stream-record/src/jdk21/PatternSwitch.java
java -cp /tmp/ps PatternSwitch
javap -c -p -cp /tmp/ps PatternSwitch | grep typeSwitch
```

## 预期输出

本机 2 核 ARM，JDK 17.0.20 / 21.0.12（节选）：

```
lambda 的类：...Demo$$Lambda$1/0x...，isHidden=true；匿名内部类：Demo$1
无捕获的 lambda 两次拿到同一个对象吗？true；捕获了变量的：false
    filter 看到 66
    filter 看到 88
    map    看到 88
findFirst → 88
CPU 核数 2，干活的线程：[ForkJoinPool.commonPool-worker-1, main]
for 循环 3.6 ms；LongStream.sum 17.9 ms；Stream<Long>.reduce 160.9 ms
外面往原列表加了一行：Cart 里变成 3 行，SafeCart 还是 2 行
Minus[off=999] → 0；switch(null) → NullPointerException
```

## 练习

先猜结果，再运行。

1. 把 findFirst 换成 `anyMatch(l -> true)`，peek 打印几行？把 sorted 换成 `limit(1)` 呢？
2. 在 PatternSwitch 的 permits 里加一个 `Coupon`，看编译报错；再加 default，报错消失。
3. 把 parallel 放进 `new ForkJoinPool(4).submit(...)` 里执行，线程名变成什么？
