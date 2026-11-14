# 半消息、定时消息、顺序消息：RocketMQ 三种特殊消息

对应页面：[改库和发消息怎么一起成功：RocketMQ 的事务消息、定时消息、顺序消息](https://archeu.github.io/study-notes/rocketmq/rocketmq-tx-delay-order.html)

交易靶场的订单服务，对着一个单节点 RocketMQ 5.5.1（NameServer + Broker，remoting 客户端直连，不起 Proxy）跑三组实验。订单库用 H2 文件库代替 MySQL。

## 怎么跑

```bash
./run.sh              # 下载并启动 RocketMQ 5.5.1，tx、delay、order 三组全跑
./run.sh tx           # RocketMQ 留着，只跑一组：tx | delay | order orderly|concurrent|rr
./run.sh stop         # 停 NameServer 和 Broker
./run.sh clean        # 停掉并删掉 data/（下载包、存储、日志、订单库都在里面）
```

需要 JDK 17 和 Maven。启动脚本把 NameServer 堆改成 256 MB、Broker 堆改成 1 GB（默认 4 GB、8 GB）；其余 Broker 参数用默认值。日志和存储都写在 `data/` 里。

`SpecialMessages` 三种模式：

| 模式 | 做什么 |
|---|---|
| `tx` | 6 单：9527 正常提交；9528 本地事务失败返回 ROLLBACK；9529 本地事务提交后、COMMIT 前进程被 halt；9530 本地事务没提交就被 halt；9531 老写法“先提交库再发”中间被杀；9532 老写法“先发再提交库”中间被杀。父进程是同一个生产者组的另一个实例，回答 Broker 的回查 |
| `delay` | 300 单，每单发一条 10 秒后的定时消息（`setDeliverTimeMs`）和一条第 3 级延迟消息（`setDelayTimeLevel(3)` = 10s）；3 秒时付掉 1/3；消费端 `UPDATE … WHERE status='NEW'` 取消没付的；再发一条 4 天后的定时消息 |
| `order` | 1000 单 × 3 个事件（PAID、SHIPPED、REFUNDED），每 10 单一批交错发到 8 个队列；`orderly` 按订单号选队列 + `MessageListenerOrderly`，`concurrent` 按订单号选队列 + `MessageListenerConcurrently`，`rr` 不选队列（轮询）+ `MessageListenerOrderly`。每条处理 sleep 1 ms |

## 预期输出

本机 2 核 ARM、JDK 17.0.21、RocketMQ 5.5.1 单节点，跑了两遍：

```
== tx 结果 ==（第一遍）
订单  库里最终状态  积分服务收到“已支付”了吗
9527  PAID        +  2.7s（此刻库里是 PAID）
9528  CANCELLED   没收到
9529  PAID        + 23.5s（此刻库里是 PAID）     ← 半消息存在 20.5s 时回查，第二遍是 31.7s
9530  NEW         没收到                          ← 回查时库里 NEW → ROLLBACK
9531  PAID        没收到                          ← 老写法：库改了，消息丢了
9532  NEW         +  7.5s（此刻库里是 NEW）       ← 老写法：消息发了，库没改

== delay 结果 ==
取消 200 单，跳过（已付款）100 单
4 天后的定时消息：被拒绝 —— CODE: 13 timer message illegal, the delay time should not be bigger than the max delay 259200000ms
实际投递比预定时间晚多少毫秒：
  定时消息 setDeliverTimeMs  中位 668 / 635 · 最晚 1064 / 1055
  延迟级别 setDelayTimeLevel 中位 65 / 63 · 最晚 159 / 107

== order ==（两遍）
orderly     消费 618 / 498 ms，最终错 0 单，新状态被旧事件盖掉 0 次
concurrent  消费 266 / 259 ms，最终错 427 / 490 单，盖掉 623 / 733 次
rr          消费 593 / 485 ms，最终错 703 / 560 单，盖掉 1120 / 856 次
```

回查时间取决于半消息写入时，Broker 每 30 秒一轮的检查任务转到了哪里：半消息不满 6 秒（`transactionTimeOut`）不查，所以落在 6～36 秒之间。

## 练习

先猜结果，再运行。

1. 把父进程 `checkLocalTransaction` 改成永远返回 `UNKNOW`，等 8 分钟以上：9529 会被回查几次，最后去了哪里？（提示：`transactionCheckMax=15`，超过的半消息被移到 `TRANS_CHECK_MAX_TIME_TOPIC`）
2. 在 `orderly` 里让订单 10003 的 SHIPPED 一直返回 `SUSPEND_CURRENT_QUEUE_A_MOMENT`：10 秒后和它同一个队列的其他订单处理到哪了？换成 `concurrent` + `RECONSUME_LATER` 呢？
3. 把 `delay` 的取消语句去掉 `AND status='NEW'`：付了款的 100 单会怎样？再想想如果取消消息先到、支付回调后到，支付那边该怎么写。
