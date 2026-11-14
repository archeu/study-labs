# 积分服务停了 8 天，消息已经被删：Kafka 的分层存储、日志压缩与位点过期

对应页面：[积分服务停了 8 天，消息已经被删：Kafka 的分层存储、日志压缩与位点过期](https://archeu.github.io/study-notes/kafka/kafka-tiered-compact.html)

交易靶场 J08 第 11 站（段与保留）的下一圈。单节点 Kafka 4.4.0（KRaft），打开分层存储；远端用 Kafka 自带的测试插件
`LocalTieredStorage`（4.4.0 在 `kafka-storage-4.4.0-test-fixtures.jar` 里，run.sh 会下载），把“对象存储”模拟成 `data/remote/` 这个目录。
时间按比例缩小：**1 天 = 60/7 秒 ≈ 8.6 秒**，这样“位点保留 7 天”正好是 `offsets.retention.minutes` 的最小值 1 分钟。

## 怎么跑

```bash
./run.sh            # 三场都跑，约 4 分钟
./run.sh outage     # 积分服务停 8 天半：order-events（保留 7 天）vs order-events-tiered（本地 1 天、总共 30 天）
./run.sh compact    # order-state 开日志压缩：每个订单只留最新状态；墓碑；空 key 和 compact+分层会被拒绝
./run.sh down       # 远端存储不可用（目录权限改成 000）：生产者、读新数据、读老数据各怎样（要先跑过 outage）
./run.sh stop       # 停 Kafka；./run.sh clean 停掉并删 data/
KAFKA_HOME=/path/to/kafka_2.13-4.4.0 ./run.sh   # 已经有 Kafka 包就不用下载
```

全部代码在 `Retention.java`，三个子命令对应三场。

## 预期输出

本机 2 核 ARM，JDK 17，Kafka 4.4.0 单节点，订单服务每 50 ms 发一条“支付成功”（同时写两个 topic，各 6 分区）：

**outage**（积分服务第 1 天末宕机，第 9.5 天修好）

| 天 | order-events 本地段 | 本地最早 offset | tiered 本地段 | 远端段 | 位点 |
|---|---|---|---|---|---|
| 2 | 12 | 0 | 12 | 6 | 在 |
| 8 | 48 | 0 | 12 | 42 | 在 |
| 9 | 48 | 179 | 12 | 48 | 没了 |
| 9.5 | 48 | 351 | 12 | 52 | 没了 |

- ① order-events，默认 `latest`：补上 0 条，宕机期间的 1452 条全丢
- ② order-events，改 `earliest`：宕机头一天那个段已经整段删了，丢 186 条
- ③ order-events-tiered：位点也没了，但按宕机前 1 秒的时间戳 `offsetsForTimes` 找回位置，补上 1470 条（多出来的 19 条是宕机前 1 秒的，靠去重表跳过），652 ms

**compact**：写 2301 条（1000 个订单 × CREATED、PAID，200 个 REFUNDED，100 个墓碑，1 条新订单）→ 压缩后 1001 条，9527 只剩 `offset 2026 REFUNDED`，最前面一条的 offset 是 1100；墓碑过了 `delete.retention.ms` 再清一轮后 902 条、墓碑 0。
空 key：`InvalidRecordException: Compacted topic cannot accept message without key in topic partition order-state-0`；
compact + `remote.storage.enable=true`：`InvalidConfigurationException: Remote log storage only supports topics with cleanup.policy=delete or cleanup.policy being an empty list.`

**down**：远端不可用时，生产者照常写（3 天 505 条）；读本地新数据的消费者 10 秒读到 199 条；读 offset 0 的消费者 15 秒读到 0 条、**不抛异常**，broker 日志 15 秒刷了 9 万多行 `RemoteStorageException`；
复制失败，本地段从 6 个涨到 18 个（正常只留 1 天，约 12 个）。远端恢复后，老数据马上读到 2626 条，本地段回落到 12 个。

## 练习

先猜结果，再运行。

1. 积分服务启动时把 `auto.offset.reset` 设成 `none`：位点不存在就抛 `NoOffsetForPartitionException`，拒绝启动并告警，而不是悄悄跳到末尾。改 `drainFrom` 试一下 ① 那一场。
2. 给 order-state 加 `min.compaction.lag.ms=60000`，压缩后从头读：9527 的 CREATED、PAID 还在吗？过了 1 分钟呢？
3. 在 `down` 那一场里，给老数据消费者加一个“LAG 30 秒不降就告警”的检查，因为它不会抛异常。
