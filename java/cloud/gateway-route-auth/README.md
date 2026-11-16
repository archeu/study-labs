# 1024 的请求进门之前：Spring Cloud Gateway、路由与鉴权

对应页面：[1024 的请求进门之前：Spring Cloud Gateway、路由与鉴权](https://study.ainiya.de/cloud/gateway-route-auth.html)（J10 全景第 2、3、4 站）

## 里面有什么

- `GatewayApp`：Spring Cloud Gateway（WebFlux 版）。路由写在 `application.yml`：`/api/orders/**` → `lb://order-svc`，`StripPrefix=1`。全局过滤器验 JWT、删掉外面带进来的 `X-User-Id` 再写上验出来的；另一个过滤器按请求头 `X-Lab-Mode` 模拟“查一次 Redis 要 200 ms”的阻塞和非阻塞两种写法。
- `Jwt`：最小的 HS256 签发和校验（只为看清网关做了什么，生产用 Spring Security 或 JJWT，非对称密钥）。
- `OrderBackend`：order-svc 替身，JDK 自带 HttpServer + Nacos 原生 API 注册；回显收到的路径和 `X-User-Id`，路径带 `slow` 就睡 5 秒。
- `Load`：并发压测小工具。

版本：Spring Boot 3.5.16、Spring Cloud 2025.0.3（Gateway 4.3.5，Reactor Netty 1.2.18）、Spring Cloud Alibaba 2025.0.0.0。Nacos 用 `../nacos-registry-config` 的容器。

## 怎么跑

```bash
cd java && mvn -q -pl cloud/gateway-route-auth package -DskipTests
cd cloud/gateway-route-auth
DOCKER="sudo docker" ./run.sh up     # 已经在跑就跳过
./run.sh demo                        # 约 2 分钟
DOCKER="sudo docker" ./run.sh down all
```

## 预期输出

2 核 ARM 虚拟机，JDK 17；网关、后端、压测工具挤在同一台机器上，所以吞吐只看比例。跑了 2 次，第二次加了预热：

```
== ① 鉴权：没带 token / 过期 token / 伪造签名
  没带：401
  过期：401
  改签名：401
== ② 正常：路由 + StripPrefix + 网关写上 X-User-Id
  port=18091 path=/orders/9527 X-User-Id=1024
== ③ 冒充：客户端自己带 X-User-Id: 2048
  port=18091 path=/orders/9527 X-User-Id=1024
  没有路由的路径：404
== ④ 过滤器里“查 Redis 要 200 ms”：阻塞 vs 非阻塞（200 个请求，并发 50）
  CPU 核数 2；处理请求的线程（连发 40 次，看响应头 X-Gw-Thread）：
        10 X-Gw-Thread: reactor-http-nio-1
        10 X-Gw-Thread: reactor-http-nio-2
        10 X-Gw-Thread: reactor-http-nio-3
        10 X-Gw-Thread: reactor-http-nio-4
模式 none  并发 50：200 个请求 1.0 s，201 个/秒，p50 218 ms，p99 342 ms
模式 async 并发 50：200 个请求 1.3 s，151 个/秒，p50 252 ms，p99 465 ms
模式 block 并发 50：200 个请求 10.8 s，19 个/秒，p50 2590 ms，p99 4524 ms
== ⑤ 后端卡 5 s：网关默认不设响应超时
  默认：200，用了 5013 ms
  response-timeout=3s：504，用了 3227 ms
```

- 2 核机器上有 4 个 `reactor-http-nio-*` 线程：Reactor Netty 的事件循环线程数默认是 max(CPU 核数, 4)。
- 阻塞写法 19 个/秒 ≈ 4 个线程 × 每秒 5 次（每次 200 ms）。

## 练习

先猜结果，再运行。

1. 把 `block` 模式的 `Thread.sleep` 换成 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`，吞吐回到多少？
2. 给路由加 `RequestRateLimiter`（需要 Redis），`KeyResolver` 用 `X-User-Id`。先猜：没带 token 的请求会被限流器怎么处理？
3. 起两个 `OrderBackend`（18091、18092），连发 10 次看端口分布；再给其中一个注册时加元数据 `version=v2`，写一个按 `X-User-Id` 挑版本的过滤器（下钻第 6 步）。
4. 把 `response-timeout` 设成 3 s 后，后端那 5 秒的活还干不干完？在 `OrderBackend` 里打印一行看看。
