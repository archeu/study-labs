# 全景：一个下单请求在线程池里

对应页面：[全景：一个下单请求在线程池里](https://archeu.github.io/study-notes/java/pano-j02-order-in-threadpool.html)

交易靶场 J02：20:00 秒杀，88 号机械键盘只有 100 件，1000 个用户同时点“下单”。
一个 Spring Boot 3.5 服务（H2 内存库代替 MySQL），扣库存有五种写法，用 `?mode=` 切换：

| mode | 写法 | 全景第几站 |
|---|---|---|
| `mem` | 内存里普通 `int`：读、判断、写回分三步 | 4、5 |
| `naive` | 先 `SELECT qty`，再 `UPDATE stock SET qty = 算好的值` | 4、5 |
| `sync` | 把 `naive` 包进 `synchronized` | 6 |
| `cas` | `AtomicInteger.compareAndSet` 预扣，再 `qty = qty - 1` | 7 |
| `db` | `UPDATE … SET qty = qty - 1 WHERE … AND qty >= 1` | 8 |

抢到以后：订单进 `ConcurrentHashMap`（第 9 站），`CompletableFuture.runAsync` 发通知（第 10 站）。
当前用户由 `UserContext` 过滤器放进 `ThreadLocal`，请求结束时 `remove()`（第 3、11 站）。

## 怎么跑

JDK 21（`--release 21`，虚拟线程那一轮要用）。

```bash
cd java
# 一条命令：起服务，自己当压测端，五种写法各跑一轮（另有一轮 db 预热不打印），打印后退出
mvn -q -pl java/pano-j02-order-in-threadpool compile exec:java \
  -Dexec.mainClass=labs.java.pano_j02_order_in_threadpool.OrderApp -Dexec.args="--rush"

# 换成虚拟线程，只跑其中几种
mvn -q -pl java/pano-j02-order-in-threadpool exec:java \
  -Dexec.mainClass=labs.java.pano_j02_order_in_threadpool.OrderApp \
  -Dexec.args="--spring.threads.virtual.enabled=true --rush=db,naive,sync"

# 或者只起服务，自己打
mvn -q -pl java/pano-j02-order-in-threadpool exec:java -Dexec.mainClass=labs.java.pano_j02_order_in_threadpool.OrderApp
curl -s -X POST -H 'X-User: 1024' 'localhost:18080/seckill?mode=naive'
```

## 预期输出

本机 2 核 ARM、JDK 21.0.12、Spring Boot 3.5.16（Tomcat 10.1.55），两次运行：

```
写法    卖出  库存剩  超卖  耗时ms  Tomcat线程  最多同时BLOCKED  CAS重试
mem      102       0     2    1486         50                0        0
naive    142       0    42    1331         51               12        0
sync     100       0     0    1593         51               47        0
cas      100       0     0     942         51                6        0
db       100       0     0     997         51               11        0

mem      101       0     1    1635         50                0        0
naive    151       0    51    1468         50                2        0
sync     100       0     0    1585         51               48        0
cas      100       0     0    1015         51                0        0
db       100       0     0     912         51                5        0
线程名举例：[http-nio-18080-exec-1, http-nio-18080-exec-10, http-nio-18080-exec-11]
异步通知跑在：Thread-552，UserContext.get() = null
```

怎么读：

- 超卖每次数量不同，但 `naive` 每次都超卖几十件；`mem` 的窗口只有几条指令，超卖得少，但不是 0。
- 50 路并发，Tomcat 就开了约 50 个 `exec` 线程：它的线程池是先加线程到 max（默认 200）再排队，和 JDK `ThreadPoolExecutor` 的顺序相反。
- `sync` 不超卖，但最多 47、48 个线程同时 BLOCKED 在同一把锁上。其他写法也有零星 BLOCKED，来自 H2 和连接池内部的锁。
- 耗时主要花在 HTTP 往返上，几种写法差得不多，这一轮不是吞吐对比。
- 2 核机器上 `ForkJoinPool.commonPool` 的并行度是 1，`CompletableFuture` 不用它，每个任务新建一个线程（`Thread-552`）。这个线程里 `ThreadLocal` 是空的。

虚拟线程那一轮：`naive` 卖出 109（还是超卖），1000 个请求用了 941 个不同的虚拟线程（`VirtualThread[#2256,tomcat-handler-1886]/runnable@ForkJoinPool-1-worker-2`）。虚拟线程让线程变便宜，不解决竞态。

## 练习

先猜结果，再运行。

1. 把 `Rush.CONCURRENCY` 改成 300，Tomcat 会开多少个线程？超过 200 的请求去哪了？
2. 把 `UserContext` 里的 `USER.remove()` 删掉，再让一部分请求不带 `X-User` 头，会看到什么？
3. `cas` 模式里，抢到 AtomicInteger 后写数据库失败（比如抛异常），库存会怎样？该怎么补回？
4. 起两个进程（不同端口）都跑 `sync` 模式、共用一个库（把 H2 换成文件库或 MySQL），还超卖吗？
