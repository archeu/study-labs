# 进 JVM 看一眼：Arthas 线上诊断

对应页面：[进 JVM 看一眼：Arthas 线上诊断](https://study.ainiya.de/observability/arthas-diagnosis.html)（J13 全景第 9 站）

全景第 9 站的现场：order-svc（Spring Boot 3.5.16，HikariCP 10 条连接，拿连接最多等 3 秒）里有个对账任务，10 个线程并行，每笔在 `@Transactional` 方法里调支付渠道查单。渠道（也做在这个应用里，`/channel/query`）被调成 20 秒才回，对账线程各攥一条连接，下单 500。不重启，attach Arthas 4.3.5 看是谁攥着连接、时间花在哪。

| 类 | 作用 |
|---|---|
| `OrderApp` | 下单接口、假支付渠道、`/admin/channel?delayMs=` 调渠道延迟、`/admin/reconcile?count=` 启动对账（10 个 `recon-N` 线程） |
| `ReconcileService` | 雷：`@Transactional` 里调远程 |
| `PayChannelClient` | 调渠道，HttpURLConnection，读超时 30 秒 |

## 怎么跑

JDK 17（attach 要 JDK，不能是 JRE）。第一次运行会把 Arthas 下载到 `data/arthas/`（已被 .gitignore 挡住）。

```bash
cd java/observability/arthas-diagnosis
./run.sh     # 约 1 分半，用 arthas-boot -c 一条条跑命令，最后 stop
```

想交互式地玩：先 `java -jar target/observability-arthas-diagnosis-1.0.jar`，照 run.sh 里的 curl 把渠道调慢、启动对账，再 `java -jar data/arthas/arthas-boot.jar` 选这个进程。

## 预期输出

本机 2026-11-15，2 核 ARM，JDK 17，Arthas 4.3.5（节选，栈帧有删减）：

```
23:30:22 支付渠道变慢：每次查单 20 秒才回
23:30:22 对账任务开始：10 个线程，60 笔（每批 10 笔、20 秒，共 2 分钟）
23:30:24 下单：
  500 3.037638s
  500 3.008514s

=== 1. 先看全局：哪几个线程最费 CPU（采 1 秒）===
（三个线程的 cpuUsage 都在 0.1% 以下，最忙的是 arthas-command-execute 自己）
  （第一次 attach 加执行用了 5.20 秒）

=== 2. 对账线程都在干什么 ===
37      recon-1                                           main                     5                RUNNABLE         0.0             0.000            0:0.011          false           true
38      recon-2                                           main                     5                RUNNABLE         0.0             0.000            0:0.011          false           true
39      recon-3                                           main                     5                RUNNABLE         0.0             0.000            0:0.008          false           true
40      recon-4                                           main                     5                RUNNABLE         0.0             0.000            0:0.006          false           true
41      recon-5                                           main                     5                RUNNABLE         0.0             0.000            0:0.009          false           true
42      recon-6                                           main                     5                RUNNABLE         0.0             0.000            0:0.010          false           true
43      recon-7                                           main                     5                RUNNABLE         0.0             0.000            0:0.008          false           true
44      recon-8                                           main                     5                RUNNABLE         0.0             0.000            0:0.004          false           true
45      recon-9                                           main                     5                RUNNABLE         0.0             0.000            0:0.007          false           true
46      recon-10                                          main                     5                RUNNABLE         0.0             0.000            0:0.006          false           true

=== 3. recon-1（id=37）的栈，从下往上读 ===
"recon-1" Id=37 RUNNABLE (in native)
    at java.base@17.0.21.0.3/sun.nio.ch.NioSocketImpl.park(NioSocketImpl.java:186)
    at java.base@17.0.21.0.3/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:290)
    at java.base@17.0.21.0.3/sun.nio.ch.NioSocketImpl.implRead(NioSocketImpl.java:314)
    at java.base@17.0.21.0.3/sun.nio.ch.NioSocketImpl.read(NioSocketImpl.java:355)
    at java.base@17.0.21.0.3/sun.nio.ch.NioSocketImpl$1.read(NioSocketImpl.java:808)
    at java.base@17.0.21.0.3/java.net.Socket$SocketInputStream.read(Socket.java:966)
    at java.base@17.0.21.0.3/sun.net.www.protocol.http.HttpURLConnection.getInputStream0(HttpURLConnection.java:1750)
    at java.base@17.0.21.0.3/sun.net.www.protocol.http.HttpURLConnection.getInputStream(HttpURLConnection.java:1644)
    at java.base@17.0.21.0.3/java.net.HttpURLConnection.getResponseCode(HttpURLConnection.java:529)
    at labs.observability.arthas_diagnosis.PayChannelClient.queryOrder(PayChannelClient.java:18)
    at labs.observability.arthas_diagnosis.ReconcileService.reconcileOne(ReconcileService.java:27)
    at org.springframework.transaction.interceptor.TransactionInterceptor$$Lambda$1331/0x000000480162e4f8.proceedWithInvocation(Unknown Source)
    at org.springframework.transaction.interceptor.TransactionAspectSupport.invokeWithinTransaction(TransactionAspectSupport.java:380)

=== 4. 池子现在什么样（直接读 HikariDataSource 对象；同时有 4 单在等连接）===
@String[10 active, 0 idle, 4 waiting]

=== 5. 线上跑的是哪版代码（反编译）===
       @Transactional
       public void reconcileOne(long orderId) {
/*26*/     this.jdbc.queryForList("SELECT id, status FROM orders WHERE id = ?", new Object[]{orderId});
/*27*/     Map r = this.payChannel.queryOrder(orderId);
/*28*/     boolean paid = Boolean.TRUE.equals(r.get("paid"));
/*29*/     this.jdbc.update("UPDATE orders SET status = ? WHERE id = ?", new Object[]{paid ? "PAID" : "UNPAID", orderId});
       }


=== 6. reconcileOne 的时间花在哪（只看得到增强以后才开始的调用，最多等 40 秒）===
`---ts=2026-11-15 23:30:42.321;thread_name=recon-8;id=44;is_daemon=true;priority=5;TCCL=org.springframework.boot.web.embedded.tomcat.TomcatEmbeddedWebappClassLoader@196ae579
    `---[20022.574576ms] labs.observability.arthas_diagnosis.ReconcileService$$SpringCGLIB$$0:reconcileOne()
        `---[100.00% 20022.529856ms ] org.springframework.cglib.proxy.MethodInterceptor:intercept()
            `---[99.99% 20019.878003ms ] labs.observability.arthas_diagnosis.ReconcileService:reconcileOne()
                +---[0.00% 0.217721ms ] org.springframework.jdbc.core.JdbcTemplate:queryForList() #26
                +---[100.00% 20019.055319ms ] labs.observability.arthas_diagnosis.PayChannelClient:queryOrder() #27
                `---[0.00% 0.489042ms ] org.springframework.jdbc.core.JdbcTemplate:update() #29

`---ts=2026-11-15 23:30:42.319;thread_name=recon-3;id=39;is_daemon=true;priority=5;TCCL=org.springframework.boot.web.embedded.tomcat.TomcatEmbeddedWebappClassLoader@196ae579

=== 7. 看参数和返回值（同样要等下一批）===
method=labs.observability.arthas_diagnosis.PayChannelClient.queryOrder location=AtExit
ts=2026-11-15 23:31:42.393; [cost=20014.186293ms] result=@ArrayList[
    @Object[][
        @Long[31],
    ],
    @LinkedHashMap[
        @String[paid]:@Boolean[true],
        @String[orderId]:@Integer[31],
    ],
]
Command execution times exceed limit: 1, so command will exit. You can set it with -n option.

23:31:43 结束，已 stop Arthas（增强的字节码都已还原）
```

## 练习

先猜结果，再运行。

1. 把 `reconcileOne` 改成先在事务外调渠道，再开一个只包含 UPDATE 的短事务（`TransactionTemplate` 或拆到另一个 Bean），重跑：下单还 500 吗？vmtool 看到的 active 是多少？
2. 第 6 条改成 `trace … reconcileOne '#cost > 10000' -n 1`，什么时候才有输出？
3. 去掉最后的 `stop`，跑完以后 `ss -ltnp | grep 3658` 看看端口还在不在。
4. 把渠道延迟调成 60 秒（超过 30 秒读超时），`thread --all | grep recon` 看线程名的编号有什么变化，为什么？
