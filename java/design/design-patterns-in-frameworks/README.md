# 420 行 if/else 怎么拆：设计模式，到 Spring 和 MyBatis 里去认

对应页面：[420 行 if/else 怎么拆：设计模式，到 Spring 和 MyBatis 里去认](https://archeu.github.io/study-notes/design/design-patterns-in-frameworks.html)

把交易靶场的算价从一个 if/else 方法，拆成策略、模板方法、责任链，再套一层代理。先搬后改：搬完和老代码逐个比，再加满减。

| 类 | 是什么 | 模式 |
|---|---|---|
| `LegacyCalc` | 老代码的样子：一种券一个分支，不认识满减 | 反面 |
| `rule/DiscountRule` 和它的实现 | 一种优惠一个类：`CashCouponRule`、`PercentCouponRule`、`Per100CouponRule`、`PromotionMinusRule`（新增） | 策略 |
| `rule/AbstractRule` | 骨架：适用吗 → 减多少 → 不许减成负数 → 记一笔。`apply()` 是 final | 模板方法 |
| `PricingChain` | 按 `order()` 排好，把报价依次递给每条规则 | 责任链 |
| `TraceProxy` | JDK 动态代理，在算价前后各加一件事 | 代理 |
| `PatternsWalkthrough` | main：比对、加满减、换顺序、套代理 | |

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.design.design_patterns_in_frameworks.PatternsWalkthrough` 的 main。
- 命令行：`cd java && mvn -q -pl design/design-patterns-in-frameworks compile exec:java -Dexec.mainClass=labs.design.design_patterns_in_frameworks.PatternsWalkthrough`

## 预期输出

2 核 ARM 虚拟机，OpenJDK 17.0.21，2026-11-16（代理类的编号每次可能不同）：

```
== 1. 先搬：三种券从 if/else 搬成三个类，行为一点不许变
老代码和新链逐个比：30 / 30 一致

== 2. 后改：加满减，就是多一个类
用户 1024 那单：满减 -40.00 → 折扣券 -36.00，应付 324.00
改了几个已有的类：0；新增：PromotionMinusRule
老代码算同一单：360.00（它不认识满减）

== 3. 链的顺序本身是业务规则
把满减的 order 从 10 改成 30：折扣券 -40.00 → 满减 -40.00，应付 320.00

== 4. 代理：不动算价的代码，在它前后加东西
  拿到的对象是 $Proxy25，不是 PricingChain，但它也是 Pricing
  [代理] 调用 quote 之前：这里可以开事务、计时、查权限
  [代理] 调用 quote 之后：拿到 324.00
```

## 练习

先猜结果，再运行。

1. 下线临时券：删掉 `Per100CouponRule`。要改几个地方？第 1 段的比对怎么调整？
2. 加一条“会员再 95 折”：先定它排在券前面还是后面，再写规则类和一个钉住顺序的算例。
3. 把 `TraceProxy` 换成手写的装饰类 `TracingPricing implements Pricing`。接口有 20 个方法时哪种写法省事？
4. 在某个规则类里加一个成员变量累加优惠，用多线程调 `quote()`，看金额会不会串。
