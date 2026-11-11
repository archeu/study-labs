# HTTP 客户端与连接池：超时怎么配

对应页面：[HTTP 客户端与连接池：超时怎么配](https://archeu.github.io/study-notes/network/http-client-pool-timeouts.html)

用 Apache HttpClient 5.6.5（classic API，JDK 8 能跑）调一个用 JDK 自带 `HttpServer` 写的假库存服务。

| 类 | 做什么 |
|---|---|
| `FakeInventoryServer` | `/reserve?delay=ms` 睡一会儿再“扣库存”；`/trickle?chunks=&gap=` 响应头马上回，body 每隔 gap 毫秒挤一个字节 |
| `InventoryClient` | 页面⑤：四个超时 + 总 deadline 都配好的客户端，`main` 演示三种情况 |
| `TimeoutLab` | 三个实验：A 读超时不是总时长；B 池子满了在客户端排队；C 超时后重试，同一个订单扣两次 |

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.network.http_client_pool_timeouts` 包下的 `InventoryClient` 或 `TimeoutLab`。
- 命令行：
  ```bash
  cd java
  mvn -q -pl network/http-client-pool-timeouts compile exec:java -Dexec.mainClass=labs.network.http_client_pool_timeouts.InventoryClient
  mvn -q -pl network/http-client-pool-timeouts compile exec:java -Dexec.mainClass=labs.network.http_client_pool_timeouts.TimeoutLab
  ```

## 预期输出

2026-11-11，ARM64 Linux 2 核，OpenJDK 17.0.20.1，HttpClient 5.6.5（编译目标 JDK 8）。

`InventoryClient`（deadline 300 ms，读超时 250 ms）：

```
正常：库存服务 5 ms 就回                       94 ms  成功 ok order=9527
库存服务卡 1 秒才回                          251 ms  失败 SocketTimeoutException: Read timed out
响应头很快，body 每 100 ms 挤一个字节，挤 10 次     300 ms  失败 SocketTimeoutException: deadline 300 ms exceeded
池子：[leased: 0; pending: 0; available: 0; max: 100]
服务端实际扣库存次数：2（9528 那次客户端已经放弃，服务端照样扣了）
```

第一次 94 ms 是冷启动（类加载、建连接）。池子里一条可用连接都没剩：读超时和 cancel 的连接都被关掉了，不会还回池子。

`TimeoutLab`：

```
== A. 读超时 1000 ms，服务端每 400 ms 挤 1 个字节，共 10 个 ==
  只配读超时                     4091 ms  成功，收到 10 字节
  再加 1500 ms 总 deadline     1500 ms  失败 SocketException: Socket closed
== B. 每个地址 5 条连接（默认值），20 个线程同时调，每次服务端 500 ms ==
  拿连接超时 = 默认 3 分钟 → {ok@1000=5, ok@1600=5, ok@2100=4, ok@2200=1, ok@500=5}
  拿连接超时 = 300 ms → {ConnectionRequestTimeoutException@300=15, ok@500=5}
== C. 读超时 300 ms，库存服务这次要 500 ms；超时后业务代码手动重试一次 ==
  第 1 次                      302 ms  失败 SocketTimeoutException: Read timed out
  第 2 次                      301 ms  失败 SocketTimeoutException: Read timed out
  客户端两次都算失败；服务端给订单 9527 扣库存的次数：2
```

B 的 `ok@1000=5` 表示有 5 个请求耗时落在 1000～1099 ms：服务端只要 500 ms，另外一半是在客户端池子里排队。

## 练习

先猜结果，再运行。

1. 把 `TimeoutLab.experimentB` 的每地址连接数从 5 改成 20，两组结果各变成什么样？
2. 给实验 C 的服务端加按 `order` 去重（同一个订单第二次直接返回上次结果），再跑一遍，扣库存次数是多少？
3. 把 `InventoryClient` 的 deadline 改成 200 ms、读超时保持 250 ms。第二个调用会在多少毫秒失败，抛的是哪个异常？
