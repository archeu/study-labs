# 大促积压 100 万条：消费组的扩容上限、重平衡风暴与共享组

对应页面：[大促积压 100 万条：消费组的扩容上限、重平衡风暴与共享组](https://archeu.github.io/study-notes/kafka/kafka-backlog-drain.html)

交易靶场的缩小版：单节点 Kafka 4.4.0（KRaft），`order-events` 6 个分区，灌 100 万条“支付成功”（key = 订单号，时间戳铺在过去 30 分钟）。
notify-svc 的每个实例是同一个 JVM 里的一个线程、各有一个 `KafkaConsumer`；发短信用 `sleep` 代替，短信平台限流是所有实例共用的令牌桶。

## 怎么跑

```bash
./run.sh            # 起 Kafka、灌积压，drain 和 storm 全跑（约 8 分钟）
./run.sh drain      # 3/6/8 台、6×8 车道（不限流 / 限 1000）、搬到 24 分区 + 24 台、共享组 12/24、跳过过期
./run.sh storm      # 经典协议 500 条一批（风暴）/ 100 条一批 / 新协议 500 条一批，时间按 1:30 缩小
./run.sh java drain inst=6 lanes=4 smsLimit=800   # Kafka 留着，自己调参数（参数见 Backlog.java 顶部）
./run.sh stop       # 停 Kafka；./run.sh clean 停掉并删 data/
```

`Backlog` 三个子命令：`fill`（灌积压）、`drain`（起若干实例消费一段时间，按最后 N 秒算稳态速度，统计重复和重平衡）、`relay`（同 key 搬到分区更多的 topic）。

## 预期输出

本机 2 核 ARM，JDK 17，kafka-clients 4.4.0，短信 20 ms 一条：

| 试法 | 稳态速度 | 看到了什么 |
|---|---|---|
| 3 台 / 6 台 / 8 台 | 149 / 298 / 297 条/s | 8 台时两台 #PARTITIONS = 0 |
| 6 台 × 8 车道 | 1721 条/s | 理论 2400，整批等最慢的车道 |
| 同上，平台限 1000/s | 1000 条/s | 卡在平台上限 |
| 搬到 24 分区 + 24 台 | 1191 条/s | 搬 100 万条 4.7 s |
| 共享组 12 / 24 个 | 596 / 1193 条/s | 消费者数可以超过分区数 |
| 6 台，跳过超过 15 分钟的 | 299 条/s | 前 10 秒跳过 67.6 万条 |
| 风暴：经典协议，25 ms × 500 条 > 10 s | 位点不动 | 60 秒发 4500 条，重复 3000 条，9 次 CommitFailedException |
| 经典协议，一批 100 条 | 120 条/s | 0 次重平衡 |
| 新协议，25 ms × 500 条 | 111 条/s | 被踢 10 次，每次马上拿回原分区，0 重复 |

## 练习

先猜结果，再运行。

1. 在 `handle()` 里按 eventId（这里用分区 + offset）去重，重跑 `./run.sh storm` 第一组：重复变 0，LAG 仍不降。
2. 处理挪到后台线程，poll 循环里 `pause()` / `resume()`，用风暴那组参数跑，目标 0 次重平衡。
3. 共享组改 `share.acknowledgement.mode=explicit`，号码以 0 结尾的 `REJECT`，随机 `RELEASE`，观察重投次数。
