# inventoryClient.reserve() 背后：OpenFeign、Dubbo 与负载均衡

对应页面：[inventoryClient.reserve() 背后：OpenFeign、Dubbo 与负载均衡](https://archeu.github.io/study-notes/cloud/feign-dubbo-loadbalancer.html)（J10 全景第 6、7、8 站）

## 里面有什么

- `inventory.InventoryStub`：库存服务替身，注册成 `inventory-svc`，`reserve` 返回自己的端口；`/chaos/slow` 让它变慢。
- `order.OrderApp` + `InventoryClient`：订单服务用 OpenFeign 调 `inventory-svc`。`/demo/once` 打印代理类和 LoadBalancer 开始挑实例时的调用栈；`/demo/burst` 统计轮询分布；`/demo/run` 每 20 ms 调一次、按秒统计落到哪台。
- `run.sh`：依赖 `../nacos-registry-config` 里的 Nacos 3.3.0 容器。

版本：Spring Boot 3.5.16、Spring Cloud 2025.0.3（OpenFeign 4.3、LoadBalancer 4.3）、Spring Cloud Alibaba 2025.0.0.0。

## 怎么跑

```bash
cd java && mvn -q -pl cloud/feign-dubbo-loadbalancer package -DskipTests
cd cloud/feign-dubbo-loadbalancer
DOCKER="sudo docker" ./run.sh up    # 已经在跑就跳过
./run.sh demo                       # 约 3 分钟
DOCKER="sudo docker" ./run.sh down all
```

## 预期输出

2 核 ARM 虚拟机，JDK 17。跑了 2 次，第一次观察窗口只有 30 秒（到第 29 秒 18082 还在接流量），第二次拉长到 50 秒：

```
== ① 一次 inventoryClient.reserve() 背后
inventoryClient 的类：jdk.proxy2.$Proxy89
InvocationHandler：feign.ReflectiveFeign$FeignInvocationHandler
这次落到：18082
LoadBalancer 开始挑实例时的调用栈（只留 feign / loadbalancer 相关的帧，栈顶在上）：
labs.cloud.feign_dubbo_loadbalancer.order.OrderApp$1.onStart
o.s.c.openfeign.loadbalancer.FeignBlockingLoadBalancerClient.lambda$execute$0
o.s.c.openfeign.loadbalancer.FeignBlockingLoadBalancerClient.execute
feign.SynchronousMethodHandler.executeAndDecode
feign.SynchronousMethodHandler.invoke
feign.ReflectiveFeign$FeignInvocationHandler.invoke
jdk.proxy2/jdk.proxy2.$Proxy89.reserve
labs.cloud.feign_dubbo_loadbalancer.order.OrderApp.once
== ② 轮询：连调 200 次
18081：100 次，平均 6 ms
18082：100 次，平均 6 ms
== ③ 18082 变慢 300 ms，再连调 200 次（轮询不看快慢）
18081：100 次，平均 4 ms
18082：100 次，平均 304 ms
== ④ LoadBalancer 缓存 35 s（默认）：18082 下线后，还有多少请求落到它
  第 5 秒：18082 → DOWN（Nacos 约 0.6 s 推给订阅方）
  00 s {18081=22, 18082=21}
  01 s {18081=21, 18082=22}
  02 s {18081=22, 18082=21}
  03 s {18081=21, 18082=22}
  04 s {18081=22, 18082=22}
  05 s {18081=22, 18082=21}
  06 s {18081=22, 18082=22}
  07 s {18081=22, 18082=22}
  08 s {18081=21, 18082=22}
  09 s {18081=22, 18082=22}
  35 s {18081=23, 18082=23}
  36 s {18081=23, 18082=22}
  37 s {18081=32, 18082=14}
  38 s {18081=46}
  39 s {18081=45}
  最后一次落到 18082：第 37 秒
== ⑤ 把缓存 ttl 改成 2 s，重启订单服务，再来一次
  第 5 秒：18082 → DOWN（Nacos 约 0.6 s 推给订阅方）
  00 s {18081=18, 18082=18}
  01 s {18081=22, 18082=21}
  02 s {18081=21, 18082=22}
  03 s {18081=22, 18082=21}
  04 s {18081=21, 18082=22}
  05 s {18081=22, 18082=22}
  06 s {18081=41, 18082=3}
  07 s {18081=44}
  08 s {18081=45}
  09 s {18081=45}
  35 s {18081=47}
  36 s {18081=46}
  37 s {18081=47}
  38 s {18081=47}
  39 s {18081=46}
  最后一次落到 18082：第 06 秒
```

- 18082 只是 DOWN、进程还在，所以这 32 秒里的请求都成功了。换成直接停进程，这些请求就全是失败。
- 订单服务启动日志里有一行 WARN：LoadBalancer 在用默认缓存，建议换成 Caffeine。

## 练习

先猜结果，再运行。

1. 在 ④ 里把 `status 18082 DOWN` 换成 `kill` 掉 18082 的进程：50 秒里会有多少次失败？加上 `spring-retry` 并打开 LoadBalancer 的重试呢？
2. 打开 `spring.cloud.loadbalancer.nacos.enabled=true`，在 Nacos 控制台把 18082 的权重改成 0.1，再跑 ②。
3. 加入 `feign-hc5` 依赖，`/demo/once` 的调用栈最底下换成了什么？
4. 把 `read-timeout` 改成 100，再跑 ③：18082 那 100 次会怎样？
