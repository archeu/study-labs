# 退款比付款先到：状态机、跳号暂存与过期副作用

对应页面：[退款比付款先到：状态机、跳号暂存与过期副作用](https://archeu.github.io/study-notes/kafka/kafka-reorder-statemachine.html)

只模拟 Kafka 之后的那一段：事件流按比例造出乱序、重复、重平衡重投、丢一条，交给两个线程并发处理。Kafka 本身怎么打乱顺序，见第一圈 `kafka-idempotent-ordering`。

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.kafka.kafka_reorder_statemachine.ReorderChaos`（参数可选：随机种子，默认 42）。
- 命令行：`cd java && mvn -q -pl kafka/kafka-reorder-statemachine compile exec:java -Dexec.mainClass=labs.kafka.kafka_reorder_statemachine.ReorderChaos`

H2 2.x 需要 Java 11，所以这个实验按 JDK 17 编译，不按 release 8。

## 预期输出

本机 2 核 ARM，JDK 17，H2 2.4.240，种子 42，跑了 4 次。事件流每次一样，线程调度不同，所以数字在一个小范围里浮动：

```
事件流：1000 单（其中退款 287 单），共 2864 条；单内乱序 300 单，重复 327 条，重平衡重投 260 条，丢了付款事件 10 单；两个线程并发处理
[S1 去重表 + 直接覆盖] 状态错 179～183 单，积分错 26～29 单，先扣后加被放弃 56～59 次
[S2 第一圈：版本守卫 + 积分只去重] 状态错 35～37 单，积分错 25～26 单，先扣后加被放弃 54～56 次
[S3 状态机 + 连续序号 + 暂存，只靠 CAS] 全对；处理完时暂存里卡着 13～16 单（丢了付款事件 10 + 竞态 3～6），回查后补齐
[S4 S3 + 先锁这一单的行] 全对；卡着的正好是丢了付款事件的 10 单，回查后补齐；CAS 冲突 0
```

第 2 次跑时 S3 回查后还剩 1 单，后来加了“孤儿暂存”检查的两次没再出现，原因没有确认。

## 练习

先猜结果，再运行。

1. 给 S2 加一条规则：扣积分违反 CHECK 时不重试，写进 `pending`，等付款生效后再放行。积分错还剩几单？状态错呢？
2. 把线程数从 2 改成 8，S3 卡住的单数和 S4 的耗时怎么变？
3. 给 `pending` 加 `arrived_at`，处理过程中每 200 条扫一次，超过 50 ms 就回查。丢了付款事件的 10 单能不能在流跑完前补齐？
