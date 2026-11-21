# 位运算与逻辑运算：订单标志位、1 亿账户打卡和移位的坑

对应页面：[位运算与逻辑运算：订单标志位、1 亿账户打卡和移位的坑](https://study.ainiya.de/algo/bit-ops.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.bit_ops` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/bit-ops compile exec:java -Dexec.mainClass=labs.algo.bit_ops.<类名>`
- 类：`OrderFlags`（订单标志位的置位 / 清位 / 翻转 / 测试、EnumSet、移位和短路的坑、常用位技巧、两版 tableSizeFor）、`AccountBits`（1 亿账户“今日已下单”：BitSet / boolean[] / HashSet<Long> 的内存和速度，要加 `-Xmx4g`）。

## 预期输出

2026-11-21，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

```
清位 IOC          f &= ~IOC         → 0000_0100（~IOC = 1111_1110）
1 << 31 = -2147483648，1 << 32 = 1，1 << 33 = 2，1L << 32 = 4294967296
-7 >> 1 = -4，-7 / 2 = -3，Math.floorDiv(-7, 2) = -4
byte b = 0xF0（-16）：b >>> 4 = 268435455，(b & 0xFF) >>> 4 = 15
s != null & s.length() > 0 → NullPointerException（& 两边都算）
BitSet     ：  12.5 MB，写 1000 万次  136 ms
boolean[]  ： 100.9 MB，写 1000 万次   69 ms
HashSet<Long>： 602.7 MB，写 1000 万次 8146 ms
```

## 练习

先猜结果，再运行。

1. `validate(int f)`：IOC 和 FOK 同时开就拒单。
2. 用 `EnumSet<Flag>` 重写，并用显式常量（不是 `ordinal()`）和 int 互转。
3. 两个 BitSet 求“两天都下过单”（and）和“今天新来的”（andNot）。
