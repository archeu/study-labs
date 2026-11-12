# 一个 400 进了两次 DispatcherServlet：Spring MVC 请求处理、过滤器与拦截器下一圈

对应页面：[一个 400 进了两次 DispatcherServlet](https://archeu.github.io/study-notes/spring/spring-mvc-dispatch.html)（Java 地图 J05 · 全景《一个请求穿过 Spring》第 3、4、5、11 站）

交易靶场订单服务的 Web 层，不连库：`POST /orders/{id}/pay`（要登录，HTTP Basic，用户 1024 / pw）和 `GET /public/orders/{id}`（不用登录）。三种写法的过滤器、一个打印分派类型的拦截器，看一个请求在 REQUEST 和 ERROR 两次分派里谁出场。

| 类 | 作用 |
|---|---|
| `DispatchLab` | 主程序：起服务、用 JDK HttpClient 发请求、打印每个组件在哪种分派里出场。场景：`filters` `ok` `bad` `conflict` `npe` `filterboom` `anon` `public` `publicboom` `permit` `audit` `bench`，不带参数全跑 |
| `LabFilters` | `PlainFilter`（普通 Filter + @Component）、`OnceFilter`（OncePerRequestFilter + @Component）、`ErrorAwareFilter`（FilterRegistrationBean 登记 REQUEST + ERROR） |
| `WebConfig` | 安全链（`--lab.permit-error=true` 放行 /error，`--lab.audit-in-security=true` 把 `AuditFilter` 也塞进安全链）、拦截器、计时用的 Request/ResponseBodyAdvice |
| `OrderController` | 付款（9527 正常、9529 金额不符走 @ExceptionHandler、9530 抛 NPE）、公开查询（id=0 抛异常） |
| `Trace` | 记录出场顺序和计时点 |

## 怎么跑

```bash
cd java
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -q -pl spring/spring-mvc-dispatch compile exec:java \
  -Dexec.mainClass=labs.spring.spring_mvc_dispatch.DispatchLab
# 只跑某几个场景
mvn -q -pl spring/spring-mvc-dispatch exec:java -Dexec.mainClass=labs.spring.spring_mvc_dispatch.DispatchLab -Dexec.args="bad publicboom permit"
# 冷的第一次请求要单独起 JVM 才准
mvn -q -pl spring/spring-mvc-dispatch exec:java -Dexec.mainClass=labs.spring.spring_mvc_dispatch.DispatchLab -Dexec.args=bench
```

## 预期输出（本机 2 核 ARM，OpenJDK 17.0.21，Spring Boot 3.5.16 / Spring 6.2.19 / Security 6.5.11）

| 场景 | 结果 |
|---|---|
| `filters` | Tomcat 上 8 个过滤器：PlainFilter 只登记 REQUEST；三个 OncePerRequestFilter 和 springSecurityFilterChain 登记全部 5 种；ErrorAwareFilter 是 REQUEST,ERROR；WsFilter 是 FORWARD,REQUEST。安全链里 11 个过滤器 |
| `ok` | 一趟，拦截器 1 次，200 |
| `bad` | 两趟：第二趟 `[ERROR] ErrorAwareFilter`、`[ERROR] Interceptor preHandle → BasicErrorController#error`，400 + JSON |
| `conflict` | 一趟，@ExceptionHandler 直接写 409 |
| `npe` | 两趟，500 + JSON |
| `filterboom` | PlainFilter 抛异常，第二趟被 Security 拒，401 空响应（请求带着正确的 Basic 头） |
| `anon` | 第一趟被 Security sendError(401)，第二趟也被拒，401 空响应 |
| `publicboom` | 公开接口抛异常，第二趟 /error 被拒，**401 空响应**（不是 500） |
| `permit` | 同上但放行 /error：500 + JSON |
| `audit` | `AuditFilter` 一个请求打两行 |
| `bench` | 热身后服务端约 1.4～1.5 ms（读 JSON 0.32～0.35、写 JSON 0.31～0.35）；新 JVM 第一次请求服务端 179～191 ms、客户端 297～364 ms |

## 练习

先猜结果，再运行。

1. 在拦截器的 `preHandle`、`afterCompletion` 开头加 `if (req.getDispatcherType() != DispatcherType.REQUEST) return …;`，跑 `bad npe`：`[ERROR] Interceptor` 两行应该消失。
2. 加一个 `@RestControllerAdvice` 接 `MethodArgumentNotValidException`，返回 400 和字段错误，跑 `bad`：整段 `[ERROR]` 应该消失，响应体是你写的格式。
3. 让 `PlainFilter` 在 `X-Boom: filter` 时直接写 429 和 JSON 后 return，不再抛异常，跑 `filterboom`：HTTP 429，没有 `[ERROR]` 行。
