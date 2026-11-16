# 它写得很快，你得知道哪里不能信：AI 辅助研发

对应页面：[它写得很快，你得知道哪里不能信：AI 辅助研发](https://study.ainiya.de/design/ai-assisted-dev.html)

两个小实验，对应交易靶场全景里 AI 助手出场的两次。这里没有调用任何模型。

| 路径 | 是什么 |
|---|---|
| `sample-order-svc/` | 7 个碰优惠金额的入口（`.java.txt`，不参与编译）。前 6 个能从接口、定时任务顺着调用找到；第 7 个 `RefundCompensateListener` 是消息队列回调，没有代码调用它 |
| `AcceptanceGate` | 四份有错的满减实现（**手写的**，模拟任务说明不清时常见的四种产出）加一份对的；每份带 12 个按自己的理解写的测试；再用三种独立的验收去跑：签字的算例、老代码的特征测试、退款入口的算例 |

## 怎么跑

```
# 任务一：全文搜索核对入口（在本目录下）
grep -rc 'discount_amount' sample-order-svc

# 任务二（在 java/ 目录下）
mvn -q -pl design/ai-assisted-dev compile exec:java -Dexec.mainClass=labs.design.ai_assisted_dev.AcceptanceGate
```

## 预期输出

2 核 ARM 虚拟机，OpenJDK 17.0.21，2026-11-16：

```
sample-order-svc/OrderController.java.txt:0
sample-order-svc/OrderExportController.java.txt:1
sample-order-svc/OrderQueryService.java.txt:1
sample-order-svc/OrderService.java.txt:2
sample-order-svc/RefundCompensateListener.java.txt:2
sample-order-svc/RefundService.java.txt:1
sample-order-svc/SettlementJob.java.txt:1
```

```
实现             | 它自己的测试 | 签字的算例 | 特征测试       | 退款入口的算例
A 满减加在券后面 | 12/12 ✓      | ✗ E3 E5    | 60/60 ✓        | ✗ 退了 103.20
B 门槛看折后金额 | 12/12 ✓      | ✗ E5       | 60/60 ✓        | 104.49 ✓
C 顺手修了舍入   | 12/12 ✓      | 5/5 ✓      | ✗ 10 组不一样  | 104.49 ✓
D 漏了退款入口   | 12/12 ✓      | 5/5 ✓      | 60/60 ✓        | ✗ 退了 116.10
对的那份         | 12/12 ✓      | 5/5 ✓      | 60/60 ✓        | 104.49 ✓
```

（列宽有调整，内容一致。）

## 练习

1. 把一句话的任务说明（“在算价里加一个满 300 减 40，要能和券叠加。写好测试。”）交给你在用的 AI 助手，让它在 `../design-patterns-in-frameworks` 里加满减规则。把它的实现接进 `AcceptanceGate`，看它踩了 A、B、C、D 里的哪几种。
2. 换成页面 ④ 里那份五样俱全的说明再做一遍，比较差别。
3. 给靶场写一份不超过 40 行的项目说明文件，开一个新会话，不做任何交代，直接让它加一条规则，看它有没有照着做。
