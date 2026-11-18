# 从电灯开关到“你好”：计算机怎样表示数据

对应页面：[从电灯开关到“你好”：计算机怎样表示数据](https://study.ainiya.de/algo/data-representation.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.data_representation` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/data-representation compile exec:java -Dexec.mainClass=labs.algo.data_representation.Bits`

## 预期输出

2026-11-18，2 核 ARM，OpenJDK 17.0.21，`--release 8` 编译（JDK 8 写法）：

```
== 1 整数：二进制与补码 ==
13          = 0000 1101
~13         = 1111 0010   (每一位取反)
-13 = ~13+1 = 1111 0011   = 0xF3
13 + (-13)  = 0000 0000   (第 9 位的进位被丢掉)

== 2 位数有限：溢出就是绕圈 ==
Integer.MAX_VALUE + 1   = -2147483648
Math.abs(MIN_VALUE)     = -2147483648
(byte) 200              = -56
(byte) 200 & 0xFF       = 200
Math.addExact(MAX, 1)   -> integer overflow

== 3 小数：0.1 存不准 ==
0.1 + 0.2               = 0.30000000000000004
0.1 的 64 位            = 0 | 011 1111 1011 | 999999999999A
new BigDecimal(0.1)     = 0.1000000000000000055511151231257827021181583404541015625
new BigDecimal("0.1")   = 0.1
(double) (2^53 + 1)     = 9007199254740992

== 4 文字：码点 → 字节 ==
A  U+0041  UTF-8: 41            length()=1  码点数=1
é  U+00E9  UTF-8: C3 A9         length()=1  码点数=1
你  U+4F60  UTF-8: E4 BD A0      length()=1  码点数=1
😀  U+1F600  UTF-8: F0 9F 98 80   length()=2  码点数=1
"你好" 的 UTF-8 字节     = E4 BD A0 E5 A5 BD
  按 GBK 去读           = 浣犲ソ
  按 ISO-8859-1 去读    = ä½ å¥½
  按 UTF-8 去读         = 你好

== 5 同样 4 个字节，换个读法 ==
字节                    = 41 42 43 44
当 int（大端）          = 1094861636
当 int（小端）          = 1145258561
当 float（大端）        = 12.141422
当文字（ASCII）         = ABCD

== 6 连到算法：二分查找的中点 ==
(lo + hi) / 2           = -397483648
(lo + hi) >>> 1         = 1750000000
100 万个元素最多比几次  = 20  (= 1000000 的二进制位数)
```

## 练习

先猜结果，再运行。

1. **安全累加**：加 `sumCents(int... cents)`，用 `Math.addExact` 累加；传两个 `Integer.MAX_VALUE` 应抛 `ArithmeticException`，改用 `long` 累加得 4294967294。
2. **读无符号 32 位**：写 `long readUInt32(byte[] b)`，每个字节先 `& 0xFF` 再移位；喂 `FF FF FF FF` 应得 4294967295，去掉 `& 0xFF` 再跑看看变成什么。
3. **按字符截断**：写 `truncate(String s, int n)` 保留前 n 个码点；`truncate("你好😀!", 3)` 应得“你好😀”，`substring(0, 3)` 会留下半个 emoji。
