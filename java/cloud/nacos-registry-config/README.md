# inventory-2 下线了，订单服务多久知道：Nacos 注册发现与配置中心

对应页面：[inventory-2 下线了，订单服务多久知道：Nacos 注册发现与配置中心](https://archeu.github.io/study-notes/cloud/nacos-registry-config.html)（J10 全景第 1、5、9、11 站）

## 里面有什么

- `InventoryApp`：库存服务 inventory-svc。启动时注册到 Nacos、从配置中心拉 `inventory-svc.yaml`；`/cfg` 同时返回 `@Value` 和 `@ConfigurationProperties` 两种注入的值；`/chaos/slow?ms=3000` 让业务接口变慢。
- `Watcher`：站在调用方的位置，订阅 inventory-svc 的实例列表、监听配置，每次推送打印一行带毫秒时间戳的日志；`publish` 子命令用来发布配置。
- `run.sh`：起 Nacos 3.3.0 容器，跑 7 个掐表实验。

版本：Spring Boot 3.5.16、Spring Cloud 2025.0.3、Spring Cloud Alibaba 2025.0.0.0（Nacos 客户端 3.0.3）、Nacos 服务端 3.3.0（Docker 镜像 `nacos/nacos-server:v3.3.0`）。

## 怎么跑

```bash
cd java && mvn -q -pl cloud/nacos-registry-config package -DskipTests
cd cloud/nacos-registry-config
DOCKER="sudo docker" ./run.sh up     # 第一次约 30 秒；会给 nacos 用户设密码 LabPass123
./run.sh demo                        # 约 2.5 分钟
DOCKER="sudo docker" ./run.sh down
```

端口：Nacos 主端口映射到 18848，gRPC 端口必须是主端口 + 1000，所以映射到 19848；两个库存实例用 18081、18082。

## 预期输出

2 核 ARM 虚拟机，JDK 17，跑了 4 次，各项数字的范围见页面⑤。其中一次：

```
1794718573636 11:56:13.636 publish inventory-svc.yaml -> true
== ① 启动两个库存实例，订阅方多久看到
看到 2 个实例：13213 ms  ← 11:56:31.216 PUSH inventory-svc [2] 18081 w=1.0  18082 w=1.0
  Started InventoryApp in 11.496 seconds (process running for 12.723)
  Started InventoryApp in 11.595 seconds (process running for 12.769)
== ② 配置中心：inventory.timeout 2000 → 800，不重启
  改之前 /cfg：{"byProperties":2000,"byValue":2000}
  @ConfigurationProperties 变成 800：发布成功后 468 ms
  订阅方收到新配置：发布成功后 155 ms
  inv-1 日志：Refresh keys changed: [inventory.timeout]
  改之后 /cfg：{"byProperties":800,"byValue":2000}   ← @Value 那个没变
== ③ inventory-2 变慢（每次 3 s），Nacos 怎么看
  调一次 reserve：3018 ms
  之后 30 s 里推送次数：0（仍是 2 个健康实例）
== ④ 主动下线 inventory-2（actuator serviceregistry → DOWN）
  订阅方只剩 1 个：583 ms  ← 11:57:09.133 PUSH inventory-svc [1] 18081 w=1.0
  再 UP 回来：616 ms  ← 11:57:09.833 PUSH inventory-svc [2] 18081 w=1.0  18082 w=1.0
== ⑤ kill -9 inventory-2
  订阅方只剩 1 个：575 ms  ← 11:57:10.441 PUSH inventory-svc [1] 18081 w=1.0
== ⑥ 重启 inventory-2，然后 kill -STOP（进程冻住，TCP 连接还在）
  重新注册：6847 ms  ← 11:57:17.345 PUSH inventory-svc [2] 18081 w=1.0  18082 w=1.0
  订阅方只剩 1 个：22990 ms  ← 11:57:42.367 PUSH inventory-svc [1] 18081 w=1.0
  kill -CONT 以后自己回来：3528 ms  ← 11:57:45.968 PUSH inventory-svc [2] 18081 w=1.0  18082 w=1.0
== ⑦ 正常停机：kill（SIGTERM），Spring 关闭时先注销
  订阅方只剩 1 个：560 ms  ← 11:57:46.568 PUSH inventory-svc [1] 18081 w=1.0
```

- 推送大约晚 0.5 秒：Nacos 服务端的推送任务默认延迟 500 ms（`nacos.naming.push.pushTaskDelay`），用来合并短时间内的多次变化。
- `kill -STOP` 那一项要 23～25 秒：客户端空闲 5 秒发一次健康检查；服务端每 3 秒查一遍连接，20 秒没动静就发探测请求，等不到回复才注销。

## 练习

先猜结果，再运行。

1. 把 `InventoryApp` 加上 `@RefreshScope`，`@Value` 那个值会不会跟着变？代价是什么？
2. 在 ⑥ 里把 `kill -STOP` 换成只断网（`sudo iptables` 丢掉 19848 的包，或者 `docker pause j10-nacos` 反过来冻服务端），订阅方多久知道？
3. 给库存服务加一个健康指示器：业务接口连续 5 次超过 1 秒，就调 `/actuator/serviceregistry` 把自己设成 DOWN。再跑 ③，看订阅方多久把它摘掉。
4. 停掉 Nacos 容器 1 分钟（`docker stop j10-nacos`），期间调 `Watcher` 的订阅结果还在不在？两个库存实例还能不能互相调？
