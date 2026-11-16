# 1024 先用上新版：灰度发布与流量治理

对应页面：[1024 先用上新版：灰度发布与流量治理](https://study.ainiya.de/cloud/gray-release.html)（J10 全景第 4、11 站）

## 里面有什么

- `GrayGateway`：Spring Cloud Gateway。全局过滤器按 Nacos 配置里的 `gray.users` 给请求打 `X-Gray: v2 / v1`（先删外面带进来的）；LoadBalancer 换成 `withDiscoveryClient().withCaching().withHints()`，按实例元数据 `hint` 挑版本。
- `Svc`：交易靶场里的一个服务实例（JDK HttpServer + Nacos 原生 API 注册，元数据带 `hint=版本`）。order-svc 收到请求后调 inventory-svc，`propagate=true` 时把 `X-Gray` 往下传、只挑同版本的库存（手写出 Feign 拦截器 + hint LoadBalancer 在做的事）；`/admin/offline` 先从 Nacos 下线自己。
- `Tool`：发布配置、以某个用户身份下单 N 次统计版本、持续下单按秒统计失败。
- `run.sh`：order v1 ×2（18101、18102）+ v2 ×1（18103），inventory v1（18111）+ v2（18112），网关 18070。

版本：Spring Boot 3.5.16、Spring Cloud 2025.0.3（Gateway 4.3.5、LoadBalancer 4.3）、Spring Cloud Alibaba 2025.0.0.0，Nacos 3.3.0（`../nacos-registry-config` 的容器）。

## 怎么跑

```bash
cd java && mvn -q -pl cloud/gray-release package -DskipTests
cd cloud/gray-release
DOCKER="sudo docker" ./run.sh up    # 已经在跑就跳过
./run.sh demo                       # 约 5 分钟
DOCKER="sudo docker" ./run.sh down all
```

## 预期输出

2 核 ARM 虚拟机，JDK 17。第一次跑的时候 LoadBalancer 写成了 `withHints().withCaching()`，网关这一跳的灰度完全没生效（1024 和 2048 都轮询到 v1、v2），改成官方文档的顺序后：

```
publish gateway.yaml -> true
== ① 灰度名单 [1024]：各下单 10 次
  用户 1024：
    10 次  order v2:18103 → inventory v2:18112
  用户 2048：
    5 次  order v1:18101 → inventory v1:18111
    5 次  order v1:18102 → inventory v1:18111
  2048 自己带 X-Gray: v2 想混进灰度：
order v1:18101 → inventory v1:18111
== ② 在 Nacos 把 2048 加进名单，不重启网关
  2048 第一次落到 v2：发布成功后 1447 ms
== ③ order v2 忘了往下传灰度标记
  用户 1024：
    5 次  order v2:18103 → inventory v1:18111
    5 次  order v2:18103 → inventory v2:18112
== ④ inventory v2 下线了：灰度请求会怎样
  用户 1024：
    6 次  order v2:18103 → inventory v1:18111
  order v2 也挂了（kill -9），马上下单：
    6 次  ERR 500
  36 秒后（网关的实例缓存过期）：
    3 次  order v1:18101 → inventory v1:18111
    3 次  order v1:18102 → inventory v1:18111
== ⑤ 发版 order v1 18102：直接 kill -9（用户 2048，每秒 20 单，第 5 秒 kill）
    00 s  成功 20  失败 0
    03 s  成功 15  失败 5
    04 s  成功 10  失败 10
    05 s  成功 10  失败 10
    06 s  成功 10  失败 10
    07 s  成功 10  失败 10
    08 s  成功 10  失败 10
    09 s  成功 10  失败 10
    10 s  成功 10  失败 10
    11 s  成功 10  失败 10
    12 s  成功 10  失败 10
    13 s  成功 10  失败 10
    14 s  成功 10  失败 10
    15 s  成功 10  失败 10
    16 s  成功 10  失败 10
    17 s  成功 10  失败 10
    18 s  成功 10  失败 10
    19 s  成功 10  失败 10
    20 s  成功 10  失败 10
    21 s  成功 10  失败 10
    22 s  成功 10  失败 10
    23 s  成功 10  失败 10
    24 s  成功 10  失败 10
    25 s  成功 10  失败 10
    26 s  成功 10  失败 10
    27 s  成功 10  失败 10
    28 s  成功 10  失败 10
    29 s  成功 10  失败 10
    30 s  成功 10  失败 10
    31 s  成功 10  失败 10
    32 s  成功 10  失败 10
    35 s  成功 20  失败 0
    合计失败 295
  等 40 秒，让网关缓存里重新有 18102
== ⑥ 发版 order v1 18102：先下线、等 40 秒、再停
    00 s  成功 20  失败 0
    05 s  成功 20  失败 0
    10 s  成功 20  失败 0
    15 s  成功 20  失败 0
    20 s  成功 20  失败 0
    25 s  成功 20  失败 0
    30 s  成功 20  失败 0
    35 s  成功 20  失败 0
    40 s  成功 20  失败 0
    45 s  成功 20  失败 0
    50 s  成功 20  失败 0
    合计失败 0
```

- ⑤ 的 kill 实际发生在压测第 3 秒左右（压测工具自己要先起一个 JVM）；之后约 30 秒里，网关缓存里还有 18102，发给它的一半请求全部失败。
- ④ 里 order v2 被 kill 后，1024 的请求先报 500（网关缓存里还有它），缓存过期后悄悄退回 v1：hint 找不到匹配实例时，会返回全部实例。

## 练习

先猜结果，再运行。

1. 把 `withCaching()` 和 `withHints()` 的顺序换回去，再跑 ①，看 1024 落到哪些实例。
2. 灰度规则从名单改成 `userId % 100 < 5`（5% 用户），写在 GrayMark 里；名单和比例同时存在时怎么合并？
3. 给网关加 `spring.cloud.loadbalancer.cache.ttl=5s`，再跑 ⑤：失败数变成多少？⑥ 里的等待可以缩到多短？
4. 给 order-svc 加一个“发 MQ 消息”的环节，灰度标记怎么跟着消息走（J08）？
