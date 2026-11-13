# 测试全绿，付款照样没回滚：JUnit 5、Mockito 与 Testcontainers

对应页面：[测试全绿，付款照样没回滚](https://archeu.github.io/study-notes/spring/spring-testing.html)（Java 地图 J05 · 全景《一个请求穿过 Spring》测试切段）

交易靶场·订单付款。顺序：查单 → 核对金额 → 改状态 CREATED→PAID → 调渠道扣款 → 记流水。渠道拒绝时，已经改掉的状态必须撤回。
`PaymentService.pay()` 是对的写法；`payCheckedMine` / `paySwallowMine` / `paySelfCallMine` 各埋一颗事务失效的雷。

这个目录没有 `main` 要跑，全靠测试：

| 测试类 | 哪一层 | 覆盖全景哪几站 | 起什么 |
|---|---|---|---|
| `PaymentServiceTest` | JUnit 5 + Mockito 单测 | 业务判断（⑧⑨ 是假的） | 什么都不起 |
| `OrderControllerWebMvcTest` | `@WebMvcTest` 切片 | ③④⑤⑪（不起 Tomcat，② 不在里面） | MVC 相关 bean + 过滤器链，Service 是 `@MockitoBean` |
| `OrderMapperMySqlTest` | `@MybatisTest` + Testcontainers | ⑧⑨ | DataSource、SqlSessionFactory、Mapper，真 MySQL 8.4 |
| `OrderMapperH2Test` | `@MybatisTest` 默认 | ⑧⑨ | 同样的 SQL 跑在内嵌 H2 上，对照组 |
| `PaymentTxMySqlTest` | `@SpringBootTest` + Testcontainers | ⑥⑦⑧⑨ | 全部 bean，真 MySQL；只有支付渠道是 `@MockitoBean` |
| `TransactionalTestTrapTest` | 同上，但测试类加了 `@Transactional` | ⑦ 被测试事务吞掉 | 演示坑：对的写法和三颗雷看起来一样 |

`MySqlContainerConfig` 把 `MySQLContainer("mysql:8.4")` 声明成带 `@ServiceConnection` 的 bean，各测试类 `@Import` 它。

## 怎么跑

需要能访问 Docker（Testcontainers 通过 `/var/run/docker.sock` 起 MySQL 容器；第一次会拉 `mysql:8.4`，约 800 MB）。

```bash
cd java
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -pl spring/spring-testing test
# 只跑不需要 Docker 的三个
mvn -pl spring/spring-testing test -Dtest='PaymentServiceTest,OrderControllerWebMvcTest,OrderMapperH2Test'
```

## 预期输出（本机 2 核 ARM，OpenJDK 17.0.21，Maven 3.6.3，Docker 27.3.1；Spring Boot 3.5.16 → JUnit 5.12.2、Mockito 5.17.0、Testcontainers 1.21.4，MySQL 8.4.13）

23 个测试全绿。热身后（镜像已在本地）整次 `mvn test` 39.6 s，其中：

| 测试类 | 上下文启动 | 整个类 |
|---|---|---|
| `PaymentServiceTest`（6 个） | 无 | 0.20 s |
| `OrderMapperH2Test` | 0.53 s | 0.62 s |
| `OrderControllerWebMvcTest` | 0.86 s | 1.34 s |
| 第一个 `@SpringBootTest` + MySQL 的类 | 18.3 s（含起容器） | 19.2 s |
| 第二个 `@SpringBootTest` 类（同一套配置） | 复用，不再启动 | 0.22 s |
| `OrderMapperMySqlTest`（另一个上下文 → 又一个容器） | 13.2 s | 13.5 s |

第一次运行要拉镜像，整次 1 分 53 秒。

`PaymentTxMySqlTest` 打印：

```
[lab] PaymentService 类 = PaymentService$$SpringCGLIB$$0，数据库 = MySQL 8.4.13，JDBC URL = jdbc:mysql://localhost:32772/test
```

渠道拒绝后订单状态（期望 CREATED）：

| 写法 | Mockito 单测 | `@SpringBootTest` + 真 MySQL | 再加测试类 `@Transactional` |
|---|---|---|---|
| `pay`（rollbackFor = Exception） | 只看得到抛异常 | CREATED | PAID（误导） |
| 雷 1 受检异常 | 只看得到抛异常 | PAID | PAID |
| 雷 2 自己 catch | 只看得到返回 FAILED | PAID | PAID |
| 雷 3 自调用 | 只看得到抛异常 | PAID | PAID |

H2 和 MySQL 对同一组 SQL：`CH-9527-a` 和 `CH-9527-A` 两个流水号，H2 插进两条，MySQL 8.4（默认排序规则 utf8mb4_0900_ai_ci，不分大小写）报 `DuplicateKeyException`。

## 练习

1. 把三颗雷逐个修好，把 `PaymentTxMySqlTest` 里对应断言从 `PAID` 改成 `CREATED`。
2. 去掉 `TransactionalTestTrapTest` 上的 `@Transactional`，看哪几个测试会红，为什么。
3. 给 `PaymentTxMySqlTest` 加一个 `@TestPropertySource(properties = "x=1")`，数一数这次 `mvn test` 起了几个 MySQL 容器、多花了几秒。
4. 把 `mysql:8.4` 换成 Boot 4.1.2：`testcontainers-mysql` 2.0.5、`org.testcontainers.mysql.MySQLContainer`、`spring-boot-webmvc-test`，跑通同一组测试。
