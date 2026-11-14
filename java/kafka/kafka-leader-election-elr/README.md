# 三台 broker 都倒了，谁能当 leader：Kafka 的 ISR、ELR 与 unclean 选举

对应页面：[三台 broker 都倒了，谁能当 leader：Kafka 的 ISR、ELR 与 unclean 选举](https://archeu.github.io/study-notes/kafka/kafka-leader-election-elr.html)
（J08 全景“一条订单消息的一生”第 5 站的下一圈）

## 怎么跑

```bash
./run.sh laggard       # 只有最先倒、落后的那台回来：等，还是 unclean 选举
./run.sh elr-kill      # 第二台 follower 是 kill -9 停的，它先回来
./run.sh elr-clean     # 第二台 follower 是正常关机停的，它先回来
./run.sh leader-first  # 最后一任 leader 先回来，第二台 follower 还没回来
./run.sh clean         # 停进程，删 data/ 下的数据和日志（Kafka 安装包留着）
```

需要 JDK 17+、Maven。脚本会下载 Kafka 4.4.0 到 `data/`，起 1 个 controller + 3 个 broker（KRaft），
建 `order-events`（6 分区、3 副本、`min.insync.replicas=2`），只盯订单 9527 所在的分区 3。每个剧本从新集群开始，约 3 分钟。

共同的前半段：发 A 批 10 条（三台都有）→ kill -9 第一个 follower（它从此落后）→ 发 B 批 10 条（只在另两台上）
→ 积分服务 `points-svc` 处理完 20 条、提交位点 20 → 停第二个 follower → 发 C 批 1 条（写不进）→ kill -9 leader。

`Drill.java` 三个动作：`send <批次> <条数>`（acks=all，逐条看回调）、`read`（从头读分区 3）、`points`（消费组 points-svc，处理完再提交）。

## 预期输出（本机 2 核 ARM，JDK 17.0.21 客户端，Kafka 4.4.0 跑在 JDK 21 上）

broker 编号每次可能不同，下面用“leader / F1（最先倒）/ F2（第二个倒）”称呼。

| 剧本 | leader 倒下后 | 先回来的那台 | 结果 |
|---|---|---|---|
| 共同 | `Isr: <空> Elr: F2,L LastKnownElr: L`，`Leader: none` | | C 批：`TimeoutException`（broker 日志里是 NotEnoughReplicas） |
| laggard | | F1：不在 ISR、ELR 里，`Leader: none` | `kafka-leader-election.sh --election-type unclean` 后 F1 当 leader，`Isr: F1`，读到 10 条（只有 A）；还是写不进，把 `min.insync.replicas` 临时改成 1 才写进 D1～D3（offset 10～12）。另两台回来后日志里 `Truncating order-events-3 to offset 10 below high watermark 20`：B 批 10 条已确认的消息没了，offset 10 变成了 D1。points-svc 位点 20 越界，按 leader epoch 发现截断，回到 10，读到 D1～D3 |
| elr-kill | | F2：kill -9 重启，被移出 ELR，`Elr: L`，`Leader: none` | L 回来后也被移出 ELR，ISR、ELR 都空，按“最后一任 leader”选 L（controller 日志记作 unclean election），A、B 20 条都在 |
| elr-clean | | F2：正常关机，留在 ELR，马上当 leader（controller 日志 `using ELR`） | 读到 20 条；`Isr: F2` 只有 1 个，D 批写不进，等 L 回来 `Isr: L,F2` 才能写 |
| leader-first | | L：被移出 ELR，可 `Elr: F2`（F2 还没回来）不空，`Leader: none` | F2 回来、也被移出 ELR 以后，才按“最后一任 leader”选 L；20 条都在 |

## 练习

先猜结果，再运行。

1. 把 `laggard` 里“临时改 min.insync.replicas=1”那一步删掉，只做 unclean 选举：订单服务的 D 批能写进去吗？积分服务能读到 A 批吗？
2. 建 topic 时把 `min.insync.replicas` 改成 1，再跑 `elr-kill`：C 批写进去了吗？leader 倒下后 `Elr` 一栏是什么？
3. 在 `laggard` 里，给 points-svc 加上 `auto.offset.reset=none`：它会抛什么异常，你打算怎么处理？
4. 把 `leader-first` 里的“F2 也回来了”换成“F2 的磁盘坏了，永远回不来”：分区 3 什么时候才有 leader？要做什么？
