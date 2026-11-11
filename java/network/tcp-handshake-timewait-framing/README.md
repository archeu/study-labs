# TCP：握手、挥手、TIME_WAIT 与粘包

对应页面：[TCP：握手、挥手、TIME_WAIT 与粘包](https://archeu.github.io/study-notes/network/tcp-handshake-timewait-framing.html)

三个类，不依赖任何库，`--release 8` 编译。`TimeWait` 要用 `ss` 命令，只能在 Linux 上跑。

| 类 | 看什么 |
|---|---|
| `Framing` | write 5 次被一次 read 读到（粘包）；write 200 KB 要 read 好几次（拆包）；加 4 字节长度前缀后一条不差 |
| `NagleDelayedAck` | 一次请求分两次 write，不开 / 开 `TCP_NODELAY` 的延迟对比 |
| `TimeWait <模式> <次数>` | `client` 客户端先关、`server` 服务端先关、`linger0` 发 RST、`leak` 服务端忘了 close；跑完用 `ss` 数各状态在哪一边 |

## 怎么跑

```bash
cd java && mvn -q -pl network/tcp-handshake-timewait-framing compile
CP=network/tcp-handshake-timewait-framing/target/classes; P=labs.network.tcp_handshake_timewait_framing
java -cp $CP $P.Framing
java -cp $CP $P.NagleDelayedAck
java -cp $CP $P.TimeWait client 500     # 也试 server / linger0 / leak
```

想看包：另开一个终端 `sudo tcpdump -i lo -nn -S 'tcp'`，再跑 `TimeWait client 1` 和 `TimeWait linger0 1`。

## 预期输出

本机：2 核 ARM，Linux 6.8，OpenJDK 17.0.20.1，回环地址。

```
== 1) 粘包：客户端 write 5 次，每次一条 ==
   服务端第 1 次 read 拿到 160 字节: reserve order=9527 sku=88 qty=2⏎…
== 2) 拆包：客户端 write 1 次，200 KB ==
   服务端 read 了 4 次: 65536 + 65536 + 65536 + 8192 = 204800 字节
== 3) 长度前缀：每条前面加 4 字节长度 ==
   第 1 条 (31 字节): reserve order=9527 sku=88 qty=2   …共 5 条

TCP_NODELAY=false  p50 41.00 ms  p99 41.15 ms  前 5 次: 1.6 41.8 41.0 41.0 41.0
TCP_NODELAY=true   p50 0.06 ms   p99 0.11 ms

client : TIME-WAIT  客户端 500 条  服务端 0 条   timer:(timewait,59sec,0)
server : TIME-WAIT  客户端 0 条    服务端 500 条
linger0: 和这个端口有关的连接一条都没留下
leak   : CLOSE-WAIT 服务端 500 条；FIN-WAIT-2 客户端 500 条
```

## 练习

先猜结果，再运行。

1. 不开 `TCP_NODELAY`，给 `NagleDelayedAck` 的客户端套上 `BufferedOutputStream`，两次 write 后一次 flush。p50 是多少？
2. 给 `TimeWait` 加一个 `keep` 模式：一条连接一问一答 500 次再关。TIME_WAIT 几条？总耗时和 `client` 比呢？
3. 在 `Framing` 的发送方把第 3 条的长度写成 `0x7fffffff`。不检查时接收方会怎样？再用 Netty 的 `LengthFieldBasedFrameDecoder(1<<20, 0, 4, 0, 4)` 重写接收方。
4. `TimeWait leak 500` 后让程序多活 90 秒，每 10 秒数一次 CLOSE-WAIT 和 FIN-WAIT-2。谁先消失？为什么另一个不会？
