# 慢在哪一段：链路追踪，OpenTelemetry 和 SkyWalking

对应页面：[慢在哪一段：链路追踪，OpenTelemetry 和 SkyWalking](https://study.ainiya.de/observability/tracing-otel-skywalking.html)（J13 全景第 6 站；AI 地图站 09 补给）

同一个 Spring Boot 3.5.16 的 jar 起两个进程：order-svc（18101）调 product-svc（18102），各自查写 H2，下单后用普通 JDK 线程池发通知。**pom 里没有任何链路依赖，代码里没有一行链路代码**，全靠 OpenTelemetry Java agent 2.33.1；span 直接发给 Jaeger 2.23.0（内存存储），再用 `show_trace.py` 从 Jaeger 的 `/api/v3/traces` 取回画成瀑布图。`/admin/hold` 模拟全景里攥着连接的对账线程。

## 怎么跑

JDK 17，aarch64（x86 机器设 `ARCH=linux-amd64`），python3。第一次运行会把 agent（26 MB）和 Jaeger 下载到 `data/`（已被 .gitignore 挡住）。

```bash
cd java/observability/tracing-otel-skywalking
./run.sh      # 约 1 分半：起 Jaeger 和两个服务 → 正常 5 单 → 攥住连接再下 2 单 → 取回两条 trace
```

跑的时候可以打开 http://localhost:16686 看 Jaeger 界面。

## 预期输出

本机 2026-11-15，2 核 ARM，JDK 17：

```
== 埋雷：order-svc 上 10 个线程攥住全部连接 15 秒，期间下 2 单 ==
500 3.064080s
500 3.024111s
  日志（trace_id 是 agent 放进 MDC 的）：
ERROR [trace_id=d19ca3eeb773a8118d7d83b736dc716c]
java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 3001ms (total=10, active=10, idle=0, waiting=0)
  通知线程（普通 JDK 线程池）的日志：
INFO [trace_id=4f07d24f76114a203d3f888ae3d2c389] 1859135 --- [order-svc] [pool-4-thread-1]

== 从 Jaeger 取回：最近一条正常的下单 ==
trace_id 4f07d24f76114a203d3f888ae3d2c389  共 6 个 span，2 个服务
POST /api/orders                           order-svc    SERVER   ████████████████████████████████████     20.4 ms  http-nio-18101-exec-7
  GET                                      order-svc    CLIENT            ███████████████████             10.5 ms  http-nio-18101-exec-7
    GET /api/products/{sku}                product-svc  SERVER                 █████████████               7.3 ms  http-nio-18102-exec-5
      SELECT shop.products                 product-svc  CLIENT                         █                   0.2 ms  http-nio-18102-exec-5
  INSERT shop.orders                       order-svc    CLIENT                                  █          0.3 ms  http-nio-18101-exec-7
  INSERT shop.notices                      order-svc    CLIENT                                    █        0.2 ms  pool-4-thread-1

== 从 Jaeger 取回：耗时超过 2 秒的那条 ==
trace_id d19ca3eeb773a8118d7d83b736dc716c  共 4 个 span，2 个服务
POST /api/orders                           order-svc    SERVER   ████████████████████████████████████   3022.4 ms  http-nio-18101-exec-1
  GET                                      order-svc    CLIENT   █                                        12.1 ms  http-nio-18101-exec-1
    GET /api/products/{sku}                product-svc  SERVER   █                                         5.8 ms  http-nio-18102-exec-9
      SELECT shop.products                 product-svc  CLIENT   █                                         0.2 ms  http-nio-18102-exec-9
```

- 通知那条 INSERT 在普通线程池 `pool-4-thread-1` 上，仍然挂在下单的根 span 下：agent 插桩了 JDK 线程池。
- 慢的那条只有开头 12 ms 被子 span 盖住，之后 3 秒空白（连接一直没拿到）；ERROR 日志里的 trace_id 和这条 trace 一样。
- Jaeger 2.23.0 上旧的 `/api/traces` 返回 404，`show_trace.py` 用的是 `/api/v3/traces`。它的 `duration_max` 匹配的是 trace 里任意一个 span，所以“正常的那条”在脚本里按根 span 的时长过滤。

## 练习

先猜结果，再运行。

1. 两个服务加 `OTEL_TRACES_SAMPLER=parentbased_traceidratio`、`OTEL_TRACES_SAMPLER_ARG=0.2`，下 20 单，Jaeger 里有几条？product-svc 会出现不带父亲的 span 吗？
2. 在 `create` 里加一个 `@WithSpan` 方法（依赖 `io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations`），睡 50 ms，看它在瀑布图哪一层。
3. order-svc 加 `-Dotel.instrumentation.executors.enabled=false`，通知那条 INSERT 还在同一棵树里吗？日志里的 trace_id 呢？
4. 把 `notifyPool.execute` 换成 `new Thread(...).start()`，再看一次。
