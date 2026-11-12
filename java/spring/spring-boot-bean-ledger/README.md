# 249 个 bean 是谁建的：Boot 自动配置与启动下一圈

对应页面：[249 个 bean 是谁建的：Boot 自动配置与启动下一圈](https://archeu.github.io/study-notes/spring/spring-boot-bean-ledger.html)
全景：[一个请求穿过 Spring](https://archeu.github.io/study-notes/java/pano-j05-request-through-spring.html) 第 1 站

交易靶场的订单服务（和全景页 `java/java/pano-j05-request-through-spring` 同一组类、同一组依赖）原样搬过来，
加一个 `LedgerLab`：启动一次，记四本账。

1. 自动配置的两道筛子：清单上的候选 → 第一道（`AutoConfigurationImportFilter`，只查 metadata）之后 → 第二道（条件注解）之后真装上的
2. 249 个 bean 定义按“谁登记的”分组；全景各站的对象出自哪张自动配置
3. 启动时间：`BufferingApplicationStartup` 的步骤，按阶段、按 bean 的自身耗时排
4. 发两次 `POST /orders/9527/pay`、`/9529/pay`，看第一个请求要多久

| 文件 | 作用 |
|---|---|
| `LedgerLab.java` | main；`-Dlab.scenario=...` 选场景，`-Dlab.detail=true` 打出每个 bean 的分组 |
| `ImportCounter.java` | `AutoConfigurationImportListener`，记下第一道筛子之后剩哪些（登记在 `META-INF/spring.factories`） |
| `Overrides.java` | 实验台用的“自己 new 一个”：`lab.own-ds`、`lab.own-om`，默认不登记 |
| `run-scenarios.sh` | 每个场景单独起 JVM 跑 N 次，只摘关键行 |
| 其余 | 订单服务本身：Controller、Service、Mapper、Security、MyBatis 插件、异步监听器 |

## 怎么跑

用 JDK 17（Boot 3.5 最低 17）。

```bash
cd java
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64   # 换成你的 JDK 17
mvn -q -pl spring/spring-boot-bean-ledger compile
spring/spring-boot-bean-ledger/run-scenarios.sh                       # 8 个场景 × 3 次
SCENARIOS="base own-ds" N=1 spring/spring-boot-bean-ledger/run-scenarios.sh   # 只跑两个，各 1 次
```

每次的完整输出在 `target/ledger/<场景>-<次>.txt`。IDE 里直接运行 `LedgerLab` 也行（VM 参数加 `-Dlab.scenario=own-ds` 之类）。
不要用 `exec:java` 量时间：那样应用跑在 Maven 的 JVM 里，启动时间不可比。

场景：

| 场景 | 改了什么 |
|---|---|
| `base` | 什么都不改 |
| `aop-jdk` | `spring.aop.proxy-target-class=false` |
| `own-ds` | 自己 `@Bean new HikariDataSource()`，只设 URL |
| `no-ds` | `spring.autoconfigure.exclude=…DataSourceAutoConfiguration` |
| `lazy` | `spring.main.lazy-initialization=true` |
| `eager-mvc` | `spring.mvc.servlet.load-on-startup=1` |
| `jackson` | `spring.jackson.property-naming-strategy=SNAKE_CASE` |
| `jackson-own` | 同上，再自己 `@Bean new ObjectMapper()` |

看哪些候选类真被加载过：

```bash
java -Xlog:class+load:file=target/ledger/classload.txt -cp target/classes:$(cat target/ledger/cp.txt) \
  labs.spring.spring_boot_bean_ledger.LedgerLab
# 再拿 target/ledger/candidates.txt / after-filter.txt / applied.txt 去和 classload.txt 对
```

## 预期输出

本机 2 核 ARM Linux，OpenJDK 17.0.21，Spring Boot 3.5.16（Spring Framework 6.2.19），每个场景 3 次：

```
== base
启动：run() 3794～3859 ms；bean 定义 249 个，已建好的单例 262 个
自动配置：清单上 158 个候选（2 个 jar 的 AutoConfiguration.imports），排除 0 个，第一道筛子后剩 43 个，第二道之后真装上 35 个
  第一道刷掉的 115 个，按条件分：{OnBeanCondition=1, OnClassCondition=111, OnWebApplicationCondition=3}
账本：A 我们写的 9 + Mapper 1；B 自动配置 86 + 90 + 17；C @Enable* 31；D 容器内部 15
  orderService → OrderService$$SpringCGLIB$$0；dataSource → HikariDataSource（DataSourceConfiguration$Hikari）
  连接池：poolName=trade-pool maximumPoolSize=5
启动步骤：spring.context.config-classes.parse classCount=99 总 1187～1282 ms
第 1 个请求：HTTP 200 {"orderId":9527,"status":"PAID"}  601～668 ms

aop-jdk      bean 247；orderService 仍是 $$SpringCGLIB$$0（没有接口）
own-ds       bean 246；poolName=HikariPool-1 maximumPoolSize=10（yml 的 hikari 配置静默失效）
no-ds        启动失败：OrderService 构造器第 0 个参数 OrderMapper 找不到
lazy         run() 3270～3418 ms；启动时单例 154 个；第 1 个请求 685～808 ms
eager-mvc    第 1 个请求 641～713 ms（和 base 差不多）
jackson      HTTP 400（amountCents 读不到 → 0 → @Positive 不过）
jackson-own  bean 250；HTTP 200，spring.jackson.* 被忽略
```

`-Xlog:class+load` 对照：第一道刷掉的 115 个只加载了 2 个（Gson、Jsonb），第二道刷掉的 8 个加载了 3 个。

## 练习

先猜结果，再运行。

1. 给 `Overrides.dataSource()` 加上 `@ConfigurationProperties("spring.datasource.hikari")`，跑 `own-ds`。预期：连接池变回 `trade-pool`、5 条。
2. 把 `Overrides.objectMapper()` 换成一个 `Jackson2ObjectMapperBuilderCustomizer`（只关 `WRITE_DATES_AS_TIMESTAMPS`），跑 `jackson-own`。预期：和 `jackson` 一样返回 400，容器里的 bean 叫回 `jacksonObjectMapper`。
3. pom 里加 `spring-boot-starter-actuator` 重跑 `base`，和之前的 `applied.txt` diff。预期：多出一批 `…actuate.autoconfigure…`，bean 数明显变多。
