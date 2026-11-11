# 全景：订单服务调库存服务的一次 RPC

对应页面：[全景：订单服务调库存服务的一次 RPC](https://archeu.github.io/study-notes/java/pano-j04-order-calls-inventory.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.java.pano_j04_order_calls_inventory` 包下的 main。
- 命令行：`cd java && mvn -q -pl java/pano-j04-order-calls-inventory compile exec:java -Dexec.mainClass=labs.java.pano_j04_order_calls_inventory.OrderCallsInventory`
- 加 `-Dexec.args=nagle` 复现 JDK HttpServer 不开 TCP_NODELAY 时每次多等约 40 ms 的坑。
- 第一次编译会由 protobuf-maven-plugin 下载 protoc 和 protoc-gen-grpc-java，从 `src/main/proto/inventory.proto` 生成消息类和 stub。

| 类 | 做什么 |
|---|---|
| `InventoryServers` | 库存服务：两个 gRPC 实例（50051、50052）和一个 HTTP/1.1 + JSON 实例（18090），记连接数、调用数、线程名；订单 9528 模拟等行锁 500 ms |
| `OrderCallsInventory` | 订单服务：假注册中心 + round_robin，依次测序列化大小、第一次和稳定后的耗时、50 线程并发的连接数、HTTP/2 帧、deadline |

## 预期输出

本机 2 核 ARM，JDK 21.0.12，gRPC 1.84.1，全在回环上，跑了 3 次：

```
protobuf 7 字节: 08 b7 4a 10 58 18 02
JSON     35 字节: {"orderId":9527,"skuId":88,"qty":2}
gRPC 第一次: 329.2 ms，失败 DEADLINE_EXCEEDED     ← 3 次里 2 次冷启动就超时
gRPC 第二次: 9.0 ms
HTTP/1.1 第一次: 95.3 ms
gRPC     稳定后 p50 0.646 ms, p99 3.310 ms
HTTP/1.1 稳定后 p50 0.835 ms, p99 3.250 ms        ← 不开 NODELAY 时 p50 41.2 ms
gRPC 实例 A :50051   连接   1 条, 调用  2501 次
gRPC 实例 B :50052   连接   1 条, 调用  2501 次
HTTP 实例 :18090     连接  27 条, 调用  5002 次     ← 3 次分别 46、27、38 条
OUTBOUND HEADERS: streamId=5005 … :path: /inventory.InventoryService/Reserve … grpc-timeout: 299936u
OUTBOUND DATA: streamId=5005 endStream=true length=12 bytes=000000000708b74a10581802
INBOUND HEADERS / INBOUND DATA length=11 / INBOUND HEADERS grpc-status: 0
客户端 302 ms 后收到: DEADLINE_EXCEEDED
服务端: 等了 500 ms 才干活；这时 Context.isCancelled()=true，库存照样扣了 2 件
```

## 练习

先猜结果，再运行。

1. 把 deadline 从 300 ms 改成 50 ms，冷启动的第一次和稳定后的调用，各会怎样？
2. 把 `round_robin` 去掉（默认 pick_first），50 线程并发时两台各收到多少次调用？
3. 慢订单超时以后，客户端重试一次，库存一共扣了几件？业务代码要怎么改才只扣一次？
4. 把 HTTP 客户端换成 `HttpClient.Version.HTTP_2`（服务端是 HttpServer，只会 HTTP/1.1），连接数会变吗？
