# 同一个词，两边是两个东西：DDD 限界上下文、聚合、领域事件

对应页面：[同一个词，两边是两个东西：DDD 限界上下文、聚合、领域事件](https://archeu.github.io/study-notes/design/ddd-bounded-context-aggregate.html)

用户 1024 那一单（199 + 129 + 72，满 300 减 40，一张 9 折券），在两个限界上下文里走一遍。为了一个进程能跑，接口调用和消息队列都换成了直接调方法，边界和真的一样。

| 包 | 里面有什么 |
|---|---|
| `promotion` | 营销上下文。聚合 `Promotion`（活动和预算）、`Coupon`（券的状态机）；领域服务 `PricingService`；报价 `Quote` |
| `ordering` | 订单上下文。聚合根 `Order`（订单行、快照、退款）；值对象 `PriceSnapshot`；事件 `OrderEvents`；向外要报价的接口 `PricingGateway`。这个包不 import 营销的任何类 |
| `app` | 把两边接起来。`PromotionPricingAdapter` 是防腐层；`PromotionEventHandler` 是营销这边的事件消费者 |
| `Walkthrough` | main：下单、支付、事件重复投递、改规则后退款、四次违反不变量的尝试、预算花超 |

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.design.ddd_bounded_context_aggregate.Walkthrough` 的 main。
- 命令行：`cd java && mvn -q -pl design/ddd-bounded-context-aggregate compile exec:java -Dexec.mainClass=labs.design.ddd_bounded_context_aggregate.Walkthrough`

## 预期输出

2 核 ARM 虚拟机，OpenJDK 17.0.21，2026-11-16：

```
== 1. 下单：订单问营销怎么算，把答案存成自己的快照
应付 324.00，每行分摊 37.81 / 24.51 / 13.68，券 LOCKED

== 2. 支付：订单发出 OrderPaid，营销听到后在自己的事务里记账
事件 OrderPaid[orderId=O1, paid=32400, promotionId=P1, promotionAmount=4000, couponId=C1]
营销处理：券已核销；预算记一笔 → 券 REDEEMED，预算已用 40.00 / 100.00
同一个事件又投了一次：券之前核销过，跳过；这张订单记过账，跳过 → 预算已用 40.00

== 3. 运营把活动改成满 400 减 50，然后用户退保温杯
订单按快照退：104.49
对照，这时找营销重算：101.59

== 4. 聚合守着的不变量
保温杯再退一次 → 拒绝：订单 O1 的第 1 行已经退过
没付款的订单退货 → 拒绝：订单 O9 还没付款，不能退
快照里一行的分摊比行原价还多 → 拒绝：第 1 行的分摊 20000 超出了行原价
另一张订单想用同一张券 → 拒绝：券 C1 现在是 REDEEMED，不能再锁

== 5. 预算在订单事务之外记账：会花超一点
O2 应付 350.00；付款前预算剩 60.00，付款后已用 90.00 / 100.00
O3 应付 350.00；付款前预算剩 10.00，付款后已用 140.00 / 100.00，超 40.00，活动结束
O4 应付 400.00（活动已结束，不再减）
```

预算设成 100 元，是为了看到花超的那一刻。

## 练习

先猜结果，再运行。

1. 整单退才退券（R5）：给 `Order` 加 `refundAll()`，发 `OrderRefunded`；营销收到后把券放回、预算回补。做完检查 `ordering` 包里仍然没有 import 营销的类。
2. 券能叠两张：先说出哪个包要改、`Order` 要不要改，再动手。
3. 预算一分不许超：在 `PromotionPricingAdapter` 里改成问价时先占预算，支付超时再放回。第 5 段的输出会变成什么？代价是什么？
