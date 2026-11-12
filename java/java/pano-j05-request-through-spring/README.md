# 全景：一个请求穿过 Spring

对应页面：[全景：一个请求穿过 Spring](https://archeu.github.io/study-notes/java/pano-j05-request-through-spring.html)

用户 1024 给订单 9527 付款：`POST /orders/9527/pay`。程序在同一个 JVM 里启动 Spring Boot 应用（Spring Boot 3.5.16、Spring Security、MyBatis、HikariCP、H2 的 MySQL 模式），再用 JDK HttpClient 发请求，打印十一站各自留下的痕迹。

| 类 | 在哪一站 |
|---|---|
| `RequestThroughSpring` | 主程序：启动、发请求、打印 |
| `WebConfig` | ③ `TimingFilter` 和 Security 配置，④⑪ 拦截器 |
| `OrderController` | ④⑤⑥ |
| `OrderService` | ⑦⑩：`@Transactional pay()`，以及反例 `payViaThis()` |
| `OrderMapper` | ⑧⑨ |
| `SqlTracePlugin` | ⑨ MyBatis 插件，抓 SQL 发出时的调用栈 |
| `PaidListener` | ⑩ `@Async @TransactionalEventListener` |

## 怎么跑

```bash
cd java
mvn -q -pl java/pano-j05-request-through-spring compile exec:java \
  -Dexec.mainClass=labs.java.pano_j05_request_through_spring.RequestThroughSpring
# 对照：换成不加密、不升级的密码编码器，去掉每个请求的 BCrypt
mvn -q -pl java/pano-j05-request-through-spring exec:java \
  -Dexec.mainClass=labs.java.pano_j05_request_through_spring.RequestThroughSpring -Dlab.fast-auth=true
```

## 预期输出（本机 2 核 ARM，JDK 21.0.13）

- `orderService → OrderService$$SpringCGLIB$$0`，`orderMapper → jdk.proxy3.$Proxy100`，249 个 bean，Security 链 11 个过滤器。
- SQL 发出时调用栈 131 层：Tomcat 30、Spring Security 32、Spring MVC / web 34、AOP + 事务 7、MyBatis 10、JDK 11、jakarta.servlet 2、自己的代码 5。
- 热身后 300 次：默认 p50 约 87 ms，其中“③④ 过滤器链（含认证）”约 82.7 ms；`-Dlab.fast-auth=true` 时 p50 约 3.9 ms，服务端整段约 2.4 ms。
- 对照 3（9529 金额不符）：409，状态仍是 CREATED；对照 4（自调用）：409，但状态变成了 PAID，短信监听器也没被调用。

## 练习

先猜结果，再运行。

1. 把 `PaidListener` 的 `@Async` 去掉、换成 `@EventListener`，在里面 `Thread.sleep(3000)` 后抛异常。付款接口要多久返回？9527 最后是什么状态？
2. 在 `changeStatus()` 之前再调一次 `findById(9527)`，打开 MyBatis 的 SQL 日志数一数 SELECT：走 `/pay` 和走 `/pay-self` 各几条？
