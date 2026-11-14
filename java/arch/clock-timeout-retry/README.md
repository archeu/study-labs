# 300 ms 超时，库存却冻了 2 件：时钟、超时与重试

对应页面：[300 ms 超时，库存却冻了 2 件：时钟、超时与重试](https://archeu.github.io/study-notes/arch/clock-timeout-retry.html)

交易靶场 J09 全景“一次下单跨三个库”的第 5、8、10 站：订单服务调库存服务冻结 2 件 sku 88。`TimeLab` 在同一个 JVM 里用 `com.sun.net.httpserver` 起一个库存服务（可售 50），订单服务用 `HttpURLConnection` 调它。只要 JDK，不用 Docker。按 JDK 8 写法（`--release 8`）。

- `timeout`：读超时 300 ms，库存服务 400 ms 才冻结完：订单服务报超时，库存却冻了
- `retry`：像 Dubbo 默认 `retries=2` 那样超时就马上再调，不带幂等键 / 带幂等键
- `storm`：库存服务重启 1 秒（全回 503），200 个线程同时下单；恢复后最多同时处理 10 个、每个 50 ms；立即重试 / 指数退避 / 指数退避 + 全抖动，每 100 ms 统计一次到达数
- `clock`（模拟）：用 `Clock.offset` 模拟 NTP 把墙上时钟往前拨 61 秒，对比 `currentTimeMillis` 和 `nanoTime` 判 60 秒超时。没有改系统时间

## 怎么跑

```bash
cd java
mvn -q -pl arch/clock-timeout-retry compile
java -cp arch/clock-timeout-retry/target/classes labs.arch.clock_timeout_retry.TimeLab all   # 或 timeout | retry | storm | clock
```

IDE 里运行 `labs.arch.clock_timeout_retry.TimeLab`，参数 `all`。

## 预期输出

本机 2 核 ARM，JDK 17.0.21。timeout、retry 跑了 2 次，毫秒数差十几毫秒，其余一样：

```
[timeout] 订单服务调 Reserve(9527, sku 88, 2)，读超时 300 ms；库存服务这次 400 ms 才处理完
    t= 312 ms  订单服务：SocketTimeoutException → 当成“失败”
    t= 423 ms  库存服务：冻结 2 件，可售/冻结 = 48/2
    t= 623 ms  查库存：可售/冻结 = 48/2  ← 超时只说明“不知道”，对方其实做完了

[retry] 像 Dubbo 默认 retries=2：超时就再调，最多调 3 次；不带幂等键
    t= 301 ms  订单服务：第 1 次调用超时，马上重试
    t= 401 ms  库存服务：冻结 2 件，可售/冻结 = 48/2
    t= 603 ms  订单服务：第 2 次调用超时，马上重试
    t= 703 ms  库存服务：冻结 2 件，可售/冻结 = 46/4
    t= 904 ms  订单服务：第 3 次调用超时，放弃，回滚
    t=1005 ms  库存服务：冻结 2 件，可售/冻结 = 44/6
    最后查库存：可售/冻结 = 44/6

[retry] 同样重试 2 次，这次带幂等键 key=9527-inventory（库存服务先查这个键见没见过）
    t= 301 ms  订单服务：第 1 次调用超时，马上重试
    t= 304 ms  库存服务：幂等键 9527-inventory 已经见过，不再冻结，返回 DUPLICATE
    t= 308 ms  订单服务：第 2 次调用返回 DUPLICATE
    t= 401 ms  库存服务：冻结 2 件，可售/冻结 = 48/2
    最后查库存：可售/冻结 = 48/2
```

`storm` 的数字每次都不一样（2 核机器上 200 个线程本身就把请求摊开了），跑了 3 次，稳定的只有这三条：

| | 指数退避 | 指数退避 + 全抖动 |
|---|---|---|
| 总请求 / 200 单 | 4.9～5.5 倍 | 5.6～5.7 倍 |
| 恢复后最挤的 100 ms | 52～75 次 | 30～36 次 |
| 最后一单成功 | 第 3.6～3.8 秒 | 第 2.9～3.0 秒 |

成功单数在三次里差很多（指数退避 136～197，全抖动 166～181），不拿它下结论。“立即重试”一次是 6 次尝试全耗在重启期间、200 单全放弃，一次是本机发得慢、拖过了恢复点。

## 练习

先猜结果，再运行。

1. 超时后先查，再决定重不重做：给库存服务加 `GET /reservation?order=9527`，订单服务超时后隔 100、200、400 ms 查三次，查到 FROZEN 就当成功。retry 段最后可售/冻结是多少？
2. 幂等表存结果：`seenKeys` 换成 `Map<String, String>`（PROCESSING / OK / FAIL），重复请求还在做就回 PROCESSING，做完了回第一次的结果。带键的 retry 段，订单服务第几次调用拿到 OK？
3. 给 storm 加重试预算：全局令牌桶，初始 100，失败扣 1，成功加 0.1，低于 50 就不再重试。总请求倍数降到多少？成功的单变多还是变少？
