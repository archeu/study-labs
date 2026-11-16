# 先把边界守住，再决定拆不拆：单体、模块化、微服务的演进

对应页面：[先把边界守住，再决定拆不拆：单体、模块化、微服务的演进](https://archeu.github.io/study-notes/design/arch-evolution-modular-monolith.html)

券服务 coupon-svc 里分两个模块：`coupon`（抢券）和 `pricing`（算价）。同一份代码放了两遍，让 Spring Modulith 的 `verify()` 各查一次。

| 包 | 是什么 |
|---|---|
| `good` | `pricing` 通过 `coupon` 的公开接口 `CouponQuery` 查券，内部实现 `RuleChain` 放在 `pricing.internal` |
| `bad` | `CouponGrabService` 直接 new 了 `pricing.internal.RuleChain`：越界，而且让两个模块成了环 |
| `VerifyDemo` | main：列出两份代码各自的模块，跑 `verify()`，打印结果 |

依赖：Spring Boot 3.5.16，`spring-modulith-core` 1.4.14。平时 `verify()` 写在测试里、依赖放 test 作用域；这里为了用 main 演示放成了 compile。

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.design.arch_evolution_modular_monolith.VerifyDemo` 的 main。
- 命令行：`cd java && mvn -q -pl design/arch-evolution-modular-monolith compile exec:java -Dexec.mainClass=labs.design.arch_evolution_modular_monolith.VerifyDemo`

## 预期输出

2 核 ARM 虚拟机，OpenJDK 17.0.21，2026-11-16（`…` 代替 `labs.design.arch_evolution_modular_monolith`，依赖明细有删减）：

```
== good：模块只走对方的公开接口
模块 coupon ← 包 ….good.coupon
模块 pricing ← 包 ….good.pricing
verify() 通过

== bad：抢券伸手进了算价的 internal
模块 coupon ← 包 ….bad.coupon
模块 pricing ← 包 ….bad.pricing
verify() 失败：Violations
- Cycle detected: Slice coupon ->
                Slice pricing ->
                Slice coupon
  1. Dependencies of Slice coupon
    - Method <….bad.coupon.CouponGrabService.grab(long)> calls constructor <….bad.pricing.internal.RuleChain.<init>()> in (CouponGrabService.java:13)
  2. Dependencies of Slice pricing
    - Method <….bad.pricing.PricingFacade.quote(long, java.lang.String)> calls method <….bad.coupon.CouponQuery.percentOf(java.lang.String)> in (PricingFacade.java:16)
- Module 'coupon' depends on non-exposed type ….bad.pricing.internal.RuleChain within module 'pricing'!
```

## 练习

先猜结果，再运行。

1. 把 `bad` 改对：“省多少”由调用方去问 `PricingFacade`，`CouponGrabService` 不再碰算价。`verify()` 应该通过。
2. 在 `pricing` 包加 `package-info.java`，写 `@ApplicationModule(allowedDependencies = "coupon")`；给 `coupon` 写一个空的 `allowedDependencies = {}`。`bad` 版会多报什么？
3. 把 `RuleChain` 从 `internal` 挪到 `pricing` 根包，`bad` 版还报几个问题？这样“修”为什么不对？
