# RPC 原理：序列化、协议、服务发现、负载均衡

对应页面：[RPC 原理：序列化、协议、服务发现、负载均衡](https://archeu.github.io/study-notes/network/rpc-serialization-discovery-lb.html)

不依赖任何框架、只用 JDK 写的迷你 RPC，大约 400 行，用 JDK 8 的写法：

| 类 | 对应 RPC 的哪一块 |
|---|---|
| `Api` | 接口包：`InventoryService` 接口和 `ReserveRequest` / `ReserveReply`，相当于 `.proto` |
| `Serializers` | 序列化：JDK 自带的（自描述）对比手写的 Compact（字段号 + varint，和 protobuf 同一个思路） |
| `Protocol` | 协议：17 字节定长头（魔数、版本、类型、序列化 id、请求 id、体长度）+ 体 |
| `Registry` | 服务发现：内存版注册中心，支持 register、unregister、subscribe 推送 |
| `LoadBalancers` | 负载均衡：轮询、随机、最少活跃 |
| `RpcClient` | 动态代理做 Stub，每个地址一条长连接，靠请求 id 把响应和等待的调用配上 |
| `RpcServer` | 每条连接一个读线程，业务线程池里用反射调实现类 |
| `MiniRpcDemo` | main：七段演示 |

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `MiniRpcDemo`。
- 命令行：`cd java && mvn -q -pl network/rpc-serialization-discovery-lb compile exec:java -Dexec.mainClass=labs.network.rpc_serialization_discovery_lb.MiniRpcDemo`

## 预期输出

2 核 ARM，JDK 17.0.20.1，跑了 3 次。第 5 段的数字每次都略有出入，其余几段每次相同（端口号除外）。

```
== 1. 序列化：同一个 ReserveRequest(9527, 88, 2) 变成多少字节 ==
JDK 序列化 127 字节 | JSON 35 字节 | Compact 9 字节：51 08 b7 4a 10 58 18 02 00
JDK 字节流里能读到的字符串：>labs.network.rpc_serialization_discovery_lb.Api$ReserveRequest | orderIdI | qtyJ | skuIdxp |
整条 RPC 请求（服务名 + 方法名 + 参数）：JDK 397 字节，Compact 86 字节
一帧 = 17 字节头 + 86 字节体 = 103 字节
头：ca fe 01 00 02 00 00 00 00 00 00 00 01 00 00 00 56

== 2. 一次调用，看起来像本地方法 ==
inventory 的真实类型：jdk.proxy1.$Proxy0
reserve(9527, 88, 2) → ReserveReply{ok=true, instance=A}

== 3. 轮询：1000 次顺序调用 ==
{A=500, B=500}

== 4. 服务发现：C 上线，A 下线 ==
C 注册后 300 次：{A=100, B=100, C=100}
A 摘除并关闭后 300 次：{B=150, C=150}

== 5. 一台变慢：fast 2 ms，slow 30 ms，16 线程共 2400 次 ==
轮询   fast 1200 次 / slow 1200 次 | 平均  16.3 ms | p90  30.2 ms | p99  31.5 ms
随机   fast 1214 次 / slow 1186 次 | 平均  16.1 ms | p90  30.2 ms | p99  31.1 ms
最少活跃 fast 2214 次 / slow  186 次 | 平均   4.4 ms | p90   2.8 ms | p99  30.3 ms

== 5b. 一台坏了但还在注册中心里：它立刻报错，16 线程共 2400 次 ==
轮询   a  800 次 / b  800 次 / bad  800 次 | 错误率 33.3%
最少活跃 a  465 次 / b  476 次 / bad 1459 次 | 错误率 60.8%      ← 3 次分别是 67.4% / 61.3% / 60.8%

== 6. 超时：客户端等 100 ms，库存要 300 ms ==
101 ms 后客户端收到：java.util.concurrent.TimeoutException: reserve 超过 100 ms 没回复 ...
此刻库存已扣次数：0
再等 400 ms，库存已扣次数：1  ← 超时不等于没做
```

JDK 8 上运行时，代理类名是 `com.sun.proxy.$Proxy0`。

## 练习

先猜结果，再运行。

1. **加幂等。** 在 `InventoryImpl` 里按 `orderId` 去重，同一个订单扣过就直接返回上次的结果。然后在 `RpcClient.call` 里加上“超时就重试 2 次”（Dubbo 默认 retries=2 的行为），把第 6 段改成重试版本，看 `deducted` 是 1 还是 3。
2. **字段兼容。** 在 `Serializers.writeValue` 写 `ReserveRequest` 的地方多写一个字段 4（`out.writeByte(4 << 3); varint(out, 7);`），读取端不改，猜 Compact 读出来会不会报错。JDK 序列化这边：先把 `ReserveRequest` 的字节存到文件，给类加一个字段再读回来；然后把 `serialVersionUID` 改成 2L，再读一次。
3. **请求 id 有什么用。** 把 `RpcClient.Connection` 里的 `pending` 换成“只记最后一个 Future”，用 16 个线程并发调用，看会出什么错。
4. **注册中心晚了一步。** 在第 4 段里先 `a.server.close()`，过 200 ms 才 `unregister`，看这 200 ms 里的调用会怎样。再给 `RpcClient` 加一个“连不上就换下一台”的逻辑。
