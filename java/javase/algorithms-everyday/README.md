# 数据结构与算法：日常和面试够用的那部分

对应页面：[数据结构与算法](https://archeu.github.io/study-notes/javase/algorithms-everyday.html)
（全景：一个订单对象的一生 · 第 4、9 站；J01 最后一个知识点）

`Demo` 5 段：热销 Top 10（全排序 / 小顶堆 / Stream）、按时间段查订单（二分 vs 全表扫）、
下单限流（滑动窗口）、对账排序（稳定性、违反约定的比较器）、TreeMap 里的红黑树（反射读结构）。

## 怎么跑

JDK 17。第 5 段反射读 TreeMap 内部，要加 `--add-opens`。

```bash
cd java
mvn -q -pl javase/algorithms-everyday compile
java --add-opens java.base/java.util=ALL-UNNAMED \
  -cp javase/algorithms-everyday/target/classes labs.javase.algorithms_everyday.Demo
```

## 预期输出

本机 2 核 ARM、JDK 17.0.20（节选）：

```
全部排序再取前 10 77.6 ms；大小为 10 的小顶堆 3.3 ms；Stream sorted().limit(10) 468.5 ms
二分：比较 40 次，命中 1621 单；从头扫：比较 1000000 次，命中 1621 单
0s 放行 … 30s 放行  40s 拒绝  55s 拒绝  61s 放行
比较器相等时返回 -1：第 3 次随机数据报 Comparison method violates its general contract!
放  1 后：(20B (10R (5B 1R ·) 15B) (30B 25R ·))
```

## 练习

1. 用 `record Sale(long skuId, long qty)` 求热销 Top 10，按销量从高到低输出。
2. 用 `TreeMap.subMap` 替换二分，再往里插新订单。
3. 把错误的比较器改成 `Integer::compare`，跑 500 次确认不再报错。
4. 预测：接着往 TreeMap 里插 12，形状变成什么？
