# 原码、反码、补码：负数在机器里怎么存，减法为什么能变成加法

对应页面：[原码、反码、补码：负数在机器里怎么存，减法为什么能变成加法](https://study.ainiya.de/algo/signed-codes.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.algo.signed_codes` 包下的 main。
- 命令行：`cd java && mvn -q -pl algo/signed-codes compile exec:java -Dexec.mainClass=labs.algo.signed_codes.<类名>`
- 类：`SignedCodes`（4 位 16 种图案按原码 / 反码 / 补码各读一遍；同一组加法三种约定各算一次；对全部 2³² 个 int 验证 `-x == ~x + 1`；最小值、溢出、byte 截断；double 的 ±0.0）。

## 预期输出

2026-11-21，2 核 Neoverse-N1 虚机，OpenJDK 17.0.21，`--release 8` 编译。

```
−3 的三种写法：+3 = 0011；原码 1011（符号位改 1）；反码 1100（+3 每位取反）；补码 1101（反码再 + 1）
   3 + -3 =  0 │ 原码 1110→−6  │ 反码 1111→−0  绕回进位后 1111→−0  │ 补码 0000→0
   5 + -3 =  2 │ 原码 0000→0   │ 反码 0001→1   绕回进位后 0010→2   │ 补码 0010→2
  全部 4294967296 个 int 都满足 -x == ~x + 1？不满足的有 0 个（2178 ms）
  Integer.MIN_VALUE = -2147483648，-MIN_VALUE = -2147483648，Math.abs(MIN_VALUE) = -2147483648
  0.0 == -0.0 → true；Double.valueOf(0.0).equals(-0.0) → false
```

## 练习

先猜结果，再运行。

1. 把 `N` 改成 8，打印 −128、−1、−100 的三种码。
2. 只用 `~`、`+`、`& 0xF` 写 4 位减法器，和 `(a - b) & 0xF` 对拍全部 256 种输入。
3. 写反码加法器（进位绕回最低位），找出哪些输入得到 −0。
