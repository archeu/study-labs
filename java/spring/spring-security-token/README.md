# 付款一次算一遍密码：Spring Security 认证与授权下一圈

对应页面：[付款一次算一遍密码](https://archeu.github.io/study-notes/spring/spring-security-token.html)（Java 地图 J05 · 全景《一个请求穿过 Spring》第 3 站）

交易靶场订单服务，不连库。密码只在登录接口验一次（BCrypt），之后用会话或 JWT 认人：`POST /login/token`、`POST /login/session`（`save=false` 模拟忘了保存，`rotate=true` 登录时换会话号）、`POST /logout/session`、`POST /orders/{id}/pay`（9527 属于 1024，9528 属于 1025，`@PreAuthorize` 做归属检查）、`POST /orders/{id}/cancel`（`hasAuthority('order:pay')`）。一条安全链同时开 `httpBasic` 和 `oauth2ResourceServer.jwt`。用户 1024、1025，密码都是 `pw`。

| 类 | 作用 |
|---|---|
| `TokenLab` | 主程序：起服务、用 JDK HttpClient 发请求、打印结果。场景：`chain` `basic` `session` `nosave` `jwt` `idor` `scope` `tamper` `expired` `logout` `fixation` `bench` `bcrypt`，不带参数全跑 |
| `SecurityConfig` | 安全链、两个用户（`{bcrypt}` 哈希）、`AuthenticationManager`、`HttpSessionSecurityContextRepository`、HS256 的 `JwtEncoder` / `JwtDecoder`、排在最前面的计时过滤器 |
| `LoginController` | 会话登录、JWT 登录（15 分钟）、登出、公开的 `/public/hello`（建一个登录前的会话） |
| `OrderController` | 付款（归属检查）、取消（按权限名） |
| `Trace` | 计时点 |

## 怎么跑

```bash
cd java
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -q -pl spring/spring-security-token compile exec:java \
  -Dexec.mainClass=labs.spring.spring_security_token.TokenLab
# 只跑某几个场景
mvn -q -pl spring/spring-security-token exec:java -Dexec.mainClass=labs.spring.spring_security_token.TokenLab -Dexec.args="jwt idor bench"
```

## 预期输出（本机 2 核 ARM，OpenJDK 17.0.21，Spring Boot 3.5.16 / Security 6.5.11）

| 场景 | 结果 |
|---|---|
| `chain` | 13 个过滤器，`BearerTokenAuthenticationFilter` 排在 `BasicAuthenticationFilter` 前面 |
| `basic` | 200，`UsernamePasswordAuthenticationToken`，权限 `[order:pay]` |
| `session` | 登录拿到 JSESSIONID，带 cookie 付款 200 |
| `nosave` | 登录 200 但没有 `Set-Cookie`，付款 401 |
| `jwt` | 令牌 183 字节，`{"alg":"HS256"}`；200，`JwtAuthenticationToken`，权限 `[SCOPE_order:pay]` |
| `idor` | 1025 付 9527 → 403（`WWW-Authenticate: Bearer error="insufficient_scope"`）；付 9528 → 200 |
| `scope` | `hasAuthority('order:pay')`：会话 200，JWT 403 |
| `tamper` | 改 sub → 401 `Invalid signature` |
| `expired` | 过期 30 秒 → 200（60 秒容差）；90 秒 → 401 `Jwt expired at …` |
| `logout` | 登出后旧 JSESSIONID 401；旧 JWT 仍 200 |
| `fixation` | 不换号时登录前后会话号相同；`rotate=true` 后不同 |
| `bench` | 进门 → Controller 中位数：Basic 84.7～85.0 ms，会话 0.46～0.51 ms，JWT 0.60～0.66 ms（两遍） |
| `bcrypt` | cost 4 / 8 / 10 / 11 / 12：1.4 / 20.7 / 82.7 / 166 / 331 ms |

## 练习

先猜结果，再运行。

1. 配一个 `JwtAuthenticationConverter`（里面的 `JwtGrantedAuthoritiesConverter` 前缀设成 `""`），挂到 `oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(...)))`，跑 `scope`：JWT 那一行应从 403 变成 200。
2. 令牌加 `jti`，登出接口把它放进内存黑名单，写一个 `OAuth2TokenValidator<Jwt>` 查它并交给 `jwtDecoder.setJwtValidator`，跑 `logout`：JWT 那一行应变成 401。
3. 时间偏差收紧到 5 秒（`new JwtTimestampValidator(Duration.ofSeconds(5))`），跑 `expired`：过期 30 秒那一行变成 401。
4. 让 `/login/session` 默认就 `changeSessionId()`，跑 `fixation`：两次的 `sessionBefore` 和 `sessionAfter` 都不同。
