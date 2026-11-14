# 交易靶场的五种消息：Kafka、RocketMQ、RabbitMQ、Redis Stream 怎么选

对应页面：[交易靶场的五种消息：Kafka、RocketMQ、RabbitMQ、Redis Stream 怎么选](https://archeu.github.io/study-notes/kafka/mq-selection.html)

只测 RabbitMQ 一侧；Kafka、RocketMQ 一侧的行为见 J08 前几页的实验。

## 怎么跑

1. 起一个 RabbitMQ 4.4.0（这台机器上默认用户启动会报 `.erlang.cookie: eacces`，所以直接以 rabbitmq 用户运行）：

   ```bash
   docker run -d --name mq-sel-rabbit --user rabbitmq -p 5672:5672 rabbitmq:4.4.0
   ```

2. 运行（JDK 17，代码按 JDK 8 写法，`maven.compiler.release=8`）：

   - IDE：打开仓库的 `java/pom.xml`，运行 `labs.kafka.mq_selection.RabbitVsLog`。
   - 命令行：`cd java && mvn -q -pl kafka/mq-selection compile exec:java -Dexec.mainClass=labs.kafka.mq_selection.RabbitVsLog`

   重跑前先删掉上次建的队列（或者 `docker rm -f mq-sel-rabbit` 重新起一个），否则场景 A 的计数会叠加。

3. 用完：`docker rm -f mq-sel-rabbit`

## 预期输出

本机 2 核 ARM，RabbitMQ 4.4.0 Docker 单节点，amqp-client 5.37.1，JDK 17，跑了 4 次结果一致：

```
== 场景 A：后来才加的消费者 ==
发了 5 条，q.points 里有 5 条
积分服务读到 [1001, 1002, 1003, 1004, 1005]，ack 后 q.points 还剩 0 条
搜索服务晚绑的 q.search 里有 0 条
stream 第一次从头读 5 条，第二次从头读 5 条
== 场景 B：订单 1003 是毒消息 ==
[nack] 其余订单照常处理完：[1001, 1002, 1004, 1005]；1003 被投递 60 次，最后一次 x-delivery-count=null, x-acquired-count=59；死信队列 0 条，原队列剩 0 条
[reject] 其余订单照常处理完：[1001, 1002, 1004, 1005]；1003 被投递 21 次，最后一次 x-delivery-count=20, x-acquired-count=20；死信队列 1 条，原队列剩 0 条
== 场景 C：按路由键分发 ==
退款服务 q.refund 收到：[1003, 1004]
VIP 服务 q.vip 收到：[1002, 1004]
== 场景 D：下单 3 秒未付就取消（quorum 队列原生延迟，4.4 起） ==
消费者收到：[2003(+0ms) 在第 10 ms 到, 2002(+1000ms) 在第 1003 ms 到, 2001(+3000ms) 在第 3003 ms 到]
```

nack 那一场 60 次是程序的保护上限：4.3 起 `basicNack` 放回不计入投递上限，不手动停会一直转。

## 练习

先猜结果，再运行。

1. 在 nack 那一场里读 `x-acquired-count`，超过 3 次就 `basicReject(tag, false)`。1003 一共被投递几次，死信队列几条？
2. 把场景 A 的 `q.search` 换成从 `s.orders` 用 `x-stream-offset=first` 读，晚到的搜索服务能补上数据吗？换成 stream 以后还能配死信吗？
3. 给 `q.notify.reject` 声明时加 `x-delivery-limit=3`，1003 被投递几次后进死信？
4. 场景 D 里把 2001 的延迟改成 30 分钟，再发一条不带延迟的：`rabbitmqctl list_queues name messages` 里能看到几条？
