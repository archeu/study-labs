# 面向对象、接口与抽象类、泛型和类型擦除

对应页面：[面向对象、接口与抽象类、泛型和类型擦除](https://archeu.github.io/study-notes/javase/oop-generics-erasure.html)
（全景：一个订单对象的一生 · 第 5、6 站）

交易靶场的 Order（实体类）、OrderLine / Product（record）、Discount（sealed 接口 + record 实现 + 一个抽象类分支），
`Demo` 分 6 段演示：封装、多态、擦除、原始类型绕过检查、桥方法、构造器调可覆盖方法的坑。

## 怎么跑

JDK 17（知识点实验统一用 17；record、sealed 都是 17 正式可用的）。

```bash
cd java
mvn -q -pl javase/oop-generics-erasure compile
java -cp javase/oop-generics-erasure/target/classes labs.javase.oop_generics_erasure.Demo

# 看字节码：invokeinterface、checkcast、Signature、PermittedSubclasses、桥方法
cd javase/oop-generics-erasure/target/classes/labs/javase/oop_generics_erasure
javap -c -p Demo.class | grep -E 'apply|checkcast'
javap -v -p Order.class | grep -A3 'java.util.List<'
javap -c -p 'Demo$ById.class'
```

## 预期输出

本机 2 核 ARM、JDK 17.0.20（节选，包名前缀略去）：

```
== 2. 多态：同一行 d.apply(sum)，跑的是各自的实现 ==
    [构造中] 满 300 减 null
  None           运行时类 Discount$None      apply(598.00) = 598.00
  Minus          运行时类 Discount$Minus     apply(598.00) = 578.00
  Percent        运行时类 Discount$Percent   apply(598.00) = 538.20
  FullReduction  运行时类 FullReduction      apply(598.00) = 568.00
== 3. 擦除 ==
  a.getClass() == b.getClass()：true（都是 java.util.ArrayList）
  但 Order.lines 字段的声明还在 class 文件里：java.util.List<OrderLine>
== 4. 原始类型绕过检查 ==
  ClassCastException 在 main():66 抛出，不在 addSomething 里
== 5. 桥方法 ==
  你写的   public int Demo$ById.compare(Order,Order)
  bridge   public int Demo$ById.compare(java.lang.Object,java.lang.Object)
```

## 练习

先猜结果，再运行。

1. 在 `Model.java` 外新建 `final class Coupon implements Discount`，编译会报什么？再把它加进 `permits`。
2. 把 ThresholdDiscount 构造器里的打印改成工厂方法 `FullReduction.of(...)` 构造完再打印，“满 300 减 null”还在吗？
3. 把 `addSomething(List raw)` 的参数改成 `List<OrderLine>`；或者用 `Collections.checkedList(lines, OrderLine.class)` 包一层，异常栈落在哪一行？
4. 把 `Order.lines()` 改成直接 `return lines;`、构造器也不复制，第 1 段会输出什么？
