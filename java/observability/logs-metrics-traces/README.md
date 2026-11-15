# 一个请求，三样数据：日志、指标、链路三件套

对应页面：[一个请求，三样数据：日志、指标、链路三件套](https://archeu.github.io/study-notes/observability/logs-metrics-traces.html)（J13 全景第 1、7 站）

交易靶场 order-svc 的最小版：下一单，同时产出三样数据，并用同一个 `trace_id` 把它们串起来。

| 数据 | 怎么来的 | 在哪看 |
|---|---|---|
| 指标 | Micrometer：HTTP 请求自动计时；`order.insert` 用 Observation 包起来；`orders.placed` 计数器；HikariCP 自带 | `GET /actuator/prometheus` |
| 日志 | Spring Boot 3.4+ 结构化日志 `logging.structured.format.console=logstash`，MDC 里的 `traceId`、`spanId` 自动成为字段 | 控制台（`run.sh` 写到 `target/run/app.log`） |
| 链路 | Micrometer Tracing + OpenTelemetry 桥；`SpanStore` 是一个 `SpanProcessor`，代替链路库把 span 收进内存 | `GET /debug/trace/{traceId}` 打印瀑布图 |

“商品服务”也在这个应用里（`/api/products/{sku}`），下单时用 `RestClient` 走 HTTP 调自己，所以能看到跨进程那一跳：CLIENT span → SERVER span。

## 怎么跑

JDK 17，端口 18080。

```bash
cd java/observability/logs-metrics-traces
./run.sh               # 正常：连下 5 单，看最后一单的三样数据
./run.sh async         # 雷 1：通知交给普通线程池，看日志里的 traceId 还在不在
./run.sh cardinality   # 雷 2：订单号放进指标标签，连下 200 单，数时间序列
```

`run.sh` 会自己打包、启动、下单、打印、关进程。IDE 里也可以直接运行 `OrderApp`，再用 curl：

```bash
curl -X POST 'localhost:18080/api/orders?userId=1024&sku=88&qty=2'
curl localhost:18080/debug/trace/<上一步返回的 traceId>
curl -s localhost:18080/actuator/prometheus | grep orders_placed
curl -s -H 'Accept: application/openmetrics-text; version=1.0.0' localhost:18080/actuator/prometheus | grep 'uri="/api/orders"' | grep trace_id
```

## 预期输出

本机 2026-11-15，JDK 17.0（aarch64），Spring Boot 3.5.16，Micrometer Prometheus 客户端 1.3.x。耗时每次不同。

```
== 响应 ==
{"traceId":"a1bb075617fdf788abafd29a3066e0c5","orderId":5}

== 日志：按 trace_id 搜 ==
{"@timestamp":"…","message":"product 88 looked up",…,"thread_name":"http-nio-18080-exec-6",…,"traceId":"a1bb0756…","spanId":"229b7dd530cada4e"}
{"@timestamp":"…","message":"order 5 created for user 1024, price 399",…,"traceId":"a1bb0756…","spanId":"d6f7924601c07517"}
{"@timestamp":"…","message":"notify user 1024 about order 5",…,"thread_name":"ctx-1",…,"traceId":"a1bb0756…","spanId":"d6f7924601c07517"}

== 指标 ==
http_server_requests_seconds_count{…,method="POST",outcome="SUCCESS",status="200",uri="/api/orders"} 5
order_insert_seconds_count{error="none",table="orders"} 5
orders_placed_total{channel="app"} 5.0
全部时间序列条数: 479

== 指标里的 exemplar（OpenMetrics 格式才有）==
http_server_requests_seconds_bucket{…,uri="/api/orders",le="0.050331646"} 4 # {span_id="21cb…",trace_id="7781…"} 0.049166387 …

== 链路 ==
trace_id a1bb075617fdf788abafd29a3066e0c5  共 4 个 span
http post /api/orders  [SERVER]           ████████████████████████████████████████  32.2 ms
  http get  [CLIENT]                         ███████████████████████████████          25.1 ms
    http get /api/products/{sku}  [SERVER]       ██████████████████████████           21.1 ms
  order.insert  [INTERNAL]                                                   █         0.9 ms
```

- `./run.sh async`：通知那一行在 `raw-1` 线程上打出，**没有 `traceId`、`spanId` 字段**；正常时在 `ctx-1` 上，带着和请求一样的 `traceId`。
- `./run.sh cardinality`：`orders_placed_by_id_total` 有 200 条序列，全部时间序列从 479 涨到 679。

## 顺手撞上的一个坑

计数器最早叫 `orders.created`，在 `/actuator/prometheus` 里却变成了 `orders_total`：Prometheus Java 客户端 1.x 会把 `_total`、`_created`、`_bucket`、`_info` 这几个保留后缀从名字末尾去掉（`PrometheusNaming`），再加上 `_total`。所以这里改名成 `orders.placed`。

## 练习

先猜结果，再运行。

1. 把 `management.tracing.sampling.probability` 删掉（回到默认值），连下 20 单，`/debug/trace/{id}` 还能查到几单？日志里的 traceId 还有吗？
2. 在 `product()` 里打印请求头 `traceparent`，对照瀑布图，看它的第二段、第三段分别等于哪个 id。
3. 给 `rawPool` 也加上 `ContextPropagatingTaskDecorator`，再跑 `./run.sh async`。
4. 在 `OrderController` 里把通知也包成一个 Observation（`order.notify`），分别放进两个线程池，看 span 树里多出来的那个 span 挂在谁下面，或者自成一棵新树。
