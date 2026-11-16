# 选了什么、放弃了什么、为什么：技术选型与 ADR

对应页面：[选了什么、放弃了什么、为什么：技术选型与 ADR](https://archeu.github.io/study-notes/design/tech-selection-adr.html)

交易靶场“满减叠加券”改造里的三条架构决策记录，外加一个把团队对 ADR 的约定写成检查的小程序。

| 路径 | 是什么 |
|---|---|
| `docs/adr/` | 三条合格的 ADR（0007 算价放进模块、0008 策略类不上规则引擎、0009 分摊存快照）和索引 `README.md` |
| `docs/adr-bad/` | 两条反例：0010 只有结论和好话；0012 编号、引用、放弃的方案都有问题 |
| `AdrLint` | 检查文件名和标题编号、状态、五节是否齐全、放弃的方案有没有写“输在”、重新看的条件、引用的 ADR 是否存在、编号是否连续、长度 |

## 怎么跑

在 `java/` 目录下：

```
mvn -q -pl design/tech-selection-adr compile exec:java \
  -Dexec.mainClass=labs.design.tech_selection_adr.AdrLint \
  -Dexec.args="design/tech-selection-adr/docs/adr design/tech-selection-adr/docs/adr-bad"
```

IDE 里运行 `AdrLint` 的 main，程序参数填两个目录的路径。

## 预期输出

2 核 ARM 虚拟机，OpenJDK 17.0.21，2026-11-16：

```
✓ adr/0007-pricing-as-module-in-coupon-svc.md
✓ adr/0008-stacking-rules-as-strategy-classes.md
✓ adr/0009-store-discount-share-snapshot.md
✗ adr-bad/0010-use-redis-for-budget.md
    - 状态“done”不在约定里：提议中、已接受、已废弃、已被 ADR-NNNN 取代
    - “决定”要用“我们……”开头，写成一句完整的话
    - 缺“## 放弃的方案”一节
    - 缺“## 重新看的条件”一节
✗ adr-bad/0012-split-pricing-svc.md
    - 标题里的编号 0013 和文件名 0012 对不上
    - 放弃的方案没写输在哪：继续加机器。
    - “重新看的条件”不能写暂无
    - 正文提到 ADR-0006，但找不到那一条
✗ 编号不连续，缺 [0011]

5 条 ADR，3 条通过，2 条有问题，编号缺 1 个
```

## 练习

先猜结果，再运行。

1. 把 0010 和 0012 改到全过。0010 要补出真正的约束，和至少两个被放弃的方案（数据库行锁、事件事后记账）。
2. 假设运营要自己加规则类型了：新写 0011 取代 0008，只改 0008 的状态一行。检查会验证“取代”指向的那条存在。
3. `AdrLint` 查不出“背景里没有约束”。想一想还能加哪条形式上的检查来逼近它，哪些只能靠评审。
