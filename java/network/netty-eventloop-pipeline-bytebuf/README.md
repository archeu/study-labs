# Netty：EventLoop、Pipeline、ByteBuf

对应页面：[Netty：EventLoop、Pipeline、ByteBuf](https://archeu.github.io/study-notes/network/netty-eventloop-pipeline-bytebuf.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.network.netty_eventloop_pipeline_bytebuf` 包下的 main。
- 命令行：`cd java && mvn -q -pl network/netty-eventloop-pipeline-bytebuf compile exec:java -Dexec.mainClass=labs.network.netty_eventloop_pipeline_bytebuf.<类名>`

## 三个类

| 类 | 对应页面 | 参数 |
|---|---|---|
| `InventoryNetty` | ⑤ 库存预占服务 + 客户端，一次 write 塞 3 个请求 | 无 |
| `BlockingEventLoop` | ⑥ 坑 1：慢处理器堵住 EventLoop | `inline`（默认）/ `offload` |
| `LeakDemo` | ⑥ 坑 2：ByteBuf 不 release | `leak`（默认）/ `fixed` |

带参数：`-Dexec.args=offload`。

## 预期输出

本机 2 核 ARM，JDK 17.0.20（`--release 8` 编译），Netty 4.2.19.Final：

```
InventoryNetty：同一条连接的日志都出自 multiThreadIoEventLoopGroup-3-1；
  48 字节（3 个请求）被切成 3 帧，每帧 writerIndex=12；第 3 次预占“库存不够”
BlockingEventLoop inline ：B 的快请求往返中位数 191 ms
BlockingEventLoop offload：B 的快请求往返中位数 1 ms
LeakDemo leak ：堆外已用 2,048 → 10,112 KB，GC 后出现 LEAK: ByteBuf.release() was not called…
LeakDemo fixed：堆外已用一直 128 KB，没有 LEAK
```

## 练习

先猜结果，再运行。

1. 在 `ReserveHandler.channelRead0` 里加 `Thread.sleep(200)`，worker 改成 1 个线程，开两个客户端同时发。第二个客户端多慢？再用 `addLast(bizGroup, "reserve", …)` 修好。参考 `BlockingEventLoop`。
2. 把 `ReserveHandler` 改成继承 `ChannelInboundHandlerAdapter`，开 `-Dio.netty.leakDetection.level=paranoid` 发 1 万个请求，找 LEAK 日志。参考 `LeakDemo`。
3. 客户端发一帧长度写成 100000 的请求，服务端会怎样？把 `maxFrameLength` 改成 `Integer.MAX_VALUE` 又会怎样？
4. 把 pipeline 顺序改成 frame → reserve → prepender，预测客户端看到什么（答案在页面 ↺ 第 4 题）。
