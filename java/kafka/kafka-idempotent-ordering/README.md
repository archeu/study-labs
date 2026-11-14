# 积分加了两次，退款跑到了付款前面：幂等消费与顺序消息

对应页面：[积分加了两次，退款跑到了付款前面：幂等消费与顺序消息](https://archeu.github.io/study-notes/kafka/kafka-idempotent-ordering.html)（Java 地图 J08 · 全景“一条订单消息的一生”第 8、2 站）

`IdempotentOrdering` 两段：

| 段 | 做什么 | 写法 |
|---|---|---|
| `dup` | 积分服务收到 `evt-9527-paid`，处理完、提交位点前“崩溃”（关掉消费者、不提交），同组新实例接手 | `none` 不去重 / `split` 去重表和加积分分两个事务 / `same` 同一个事务 / `offset` 位点和积分存同一个库，分到分区时 `seek` |
| `order` | 1000 单 × 3 条事件（PAID v1 → SHIPPED v2 → REFUNDED v3）交错着发，订单视图服务消费 | `seq` 一条条 / `nokey` 发送不填 key / `pool` 8 线程池 / `lanes` 按订单号 8 条车道 / `guard` 池子 + 版本号守卫 |

积分服务的库用 H2 内存库（MySQL 模式）代替 MySQL，唯一键和事务的语义一样。

## 怎么跑

需要 JDK 17+ 和 Maven（Kafka 4 客户端要求 Java 11+，所以这页不按 JDK 8 编译）。Kafka 放在 `KAFKA_HOME`，不设就下载 4.4.0 到 `./data/`。

```bash
./run.sh              # 起单节点 Kafka 4.4.0（KRaft，端口 19192/19193），dup 四种 + order 五种全跑
./run.sh dup split    # Kafka 留着，只跑一种
./run.sh order lanes
./run.sh stop         # 停掉 Kafka
./run.sh clean        # 停掉，并删掉 data/
```

## 预期输出（本机 2 核 ARM，JDK 17.0.21，Kafka 同机）

```
== dup/none：积分服务处理 evt-9527-paid，处理完、提交位点前崩溃
   实例 1 收到 evt-9527-paid（分区 3 offset 0）：加 798 分，崩溃，积分 +798，去重表 0 行
   实例 2 收到 evt-9527-paid（分区 3 offset 0）：又加 798 分，积分 +1596，去重表 0 行
== dup/split
   实例 1 收到 …：去重表写好了，加积分前崩溃，积分 +0，去重表 1 行
   实例 2 收到 …：去重表里已有，跳过，积分 +0，去重表 1 行
== dup/same
   实例 1 收到 …：去重 + 加 798 分一起提交，然后崩溃，积分 +798，去重表 1 行
   实例 2 收到 …：撞唯一键，回滚，跳过，积分 +798，去重表 1 行
== dup/offset
   实例 1 收到 …：加 798 分 + 位点 1 一起提交，然后崩溃，积分 +798，去重表 0 行
   实例 2：8 秒内没收到消息（位点在库里，已经越过这条）
== order/seq   1000 单 × 3 条：用时  2878 ms；新状态被旧事件盖掉    0 次；最终不是 REFUNDED 的订单    0 个
== order/nokey 1000 单 × 3 条：用时  2869 ms；新状态被旧事件盖掉    5 次；最终不是 REFUNDED 的订单    5 个
== order/pool  1000 单 × 3 条：用时   593 ms；新状态被旧事件盖掉  751 次；最终不是 REFUNDED 的订单  487 个
== order/lanes 1000 单 × 3 条：用时   588 ms；新状态被旧事件盖掉    0 次；最终不是 REFUNDED 的订单    0 个
== order/guard 1000 单 × 3 条：用时   577 ms；新状态被旧事件盖掉    0 次；最终不是 REFUNDED 的订单    0 个；当成旧事件丢掉 851 条
```

order 段跑了两遍：pool 两次都是 487 单错（倒退 751 / 760 次），nokey 两次都是 5 单，guard 丢掉 851 / 845 条；毫秒数只看相对大小。

## 练习

先猜结果，再运行。

1. 加一个 `grow` 模式：发到一半用 `Admin.createPartitions` 把分区从 6 加到 12，消费端一条条处理。错几单？再加上版本号守卫呢？
2. 在 `lanes` 模式里实现“只提交连续完成的前缀”，处理到一半 `kill -9`，数一数新实例重做了多少条；再把积分换成去重表，确认重做的没有多加分。
3. 把 `guard` 的逻辑搬到积分上（PAID +798、REFUNDED −798），积分净值不为 0 的订单有多少？
