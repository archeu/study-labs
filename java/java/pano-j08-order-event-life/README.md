# 全景：一条订单消息的一生

对应页面：[全景：一条订单消息的一生](https://archeu.github.io/study-notes/java/pano-j08-order-event-life.html)（Java 地图 J08 · 消息队列）

订单 9527 付款成功，订单服务往 `order-events` 发一条 `ORDER_PAID` 事件（key `"9527"`），库存、积分、通知三个消费组各消费一次、各自提交位点。
`OrderEventLife` 用同一个 JVM 里的三个消费者模拟三个服务，各属一个组。

## 怎么跑

需要 JDK 17+（页面用的是 JDK 21）和 Maven。Kafka 放在 `KAFKA_HOME`，不设就下载 4.4.0 到 `./data/`。

```bash
./run.sh          # 起 Kafka 4.4.0（KRaft：1 controller + 3 broker），建 topic，四段全跑：trace → dump → isr → dup
./run.sh trace    # 集群留着，只跑某一段（trace / dump / isr / dup）
./run.sh stop     # 停掉 4 个 Kafka 进程
./run.sh clean    # 停掉，并删掉 data/ logs/
```

topic：`order-events`，6 分区 × 3 副本，`min.insync.replicas=2`；其余全是默认值。生产者用默认（`acks=all`、幂等、`linger.ms=5`），消费者关掉自动提交。

| 段 | 做什么 | 对应全景 |
|---|---|---|
| trace | 三个组先加入，再发 9527 那条，逐站计时；然后连发 50 条，`linger.ms=5` 和 `0` 各一轮 | 第 2～10 站 |
| dump | `kafka-dump-log.sh` 翻分区 3 的段文件，看 9527 那一批的 producerId、序号、位置 | 第 3、4、11 站 |
| isr | 逐个停掉分区 3 的两个 follower，每停一个发一条；最后拉起来 | 第 5 站 |
| dup | 积分服务处理完、没提交就关掉，同组新实例再收到一次，靠 eventId 去重 | 第 8、9 站 |

## 预期输出（本机 2 核 ARM，JDK 21.0.13，4 个 Kafka JVM 同机）

```
== trace：订单 9527 付款成功，发一条 ORDER_PAID
   2  分区器：key "9527" → murmur2 → 正数 % 6 = 分区 3
      分区 3：leader = broker 3，副本 [3, 1, 2]，ISR [1, 2, 3]
   7  三个组加入并分到分区：{notify-svc=3226, points-svc=3176, inventory-svc=3179} ms
      预热一条（含拉元数据、建连接、拿 producerId）：202.4 ms
   3  send() 返回（只进了攒批区）：805 µs
   6  ACK 回来（acks=all，linger.ms=5）：11.74 ms，分区 3，offset 31
   8  inventory-svc  收到：发出后 11.79 ms，分区 3 offset 31；9 commitSync 16.59 ms
   8  points-svc     收到：发出后 13.82 ms，分区 3 offset 31；9 commitSync 14.05 ms
   8  notify-svc     收到：发出后 13.22 ms，分区 3 offset 31；9 commitSync 15.56 ms
== isr：订单 9527 所在分区，逐个停掉 follower
 Topic: order-events Partition: 3 Leader: 3 Replicas: 3,1,2 Isr: 1,2,3
   停掉 broker 1（follower） → Isr: 2,3，发一条 → ACK
   停掉 broker 2（follower） → Isr: 3，发一条 →
      → 失败：TimeoutException（6.6 s 后放弃）
      （broker 3 日志：NotEnoughReplicasException … ISR : 1 is insufficient to satisfy the min.isr requirement of 2）
== dup：积分服务给 1024 加了 798 分，还没提交位点进程就没了
   实例 1 收到 evt-9527-paid（分区 3 offset 1）：第一次，加 798 分，当前积分 +798
   实例 2 收到 evt-9527-paid（分区 3 offset 1）：去重表里已有，跳过，当前积分 +798
   投递 2 次，积分只加了 798（没有去重会是 1596）
```

offset 每跑一次都会往后涨。这台机器上毫秒数波动很大（ACK 11.7～18.5 ms），只看相对大小；`linger.ms=5` 和 `0` 的对比两次结果相反，分不出来。

## 练习

先猜结果，再运行。

1. 把建 topic 时的 `min.insync.replicas` 改成 1，再跑 `./run.sh isr`：只剩一个副本时能写进去吗？这时停掉 leader，再拉起两个 follower，那条还在不在？
2. 打开 `enable.auto.commit`，把处理丢给线程池、每条睡 10 秒，中途 `kill -9`：丢了几条？
3. 把 order-events 加到 12 个分区，再发 9527：还在分区 3 吗？
4. 让 notify-svc 每条睡 1 秒，连发 10 万条：LAG 怎么涨？`max.poll.records`、`max.poll.interval.ms` 怎么配它才不被踢出组？
5. `dup` 里去掉去重表，积分变成多少？
