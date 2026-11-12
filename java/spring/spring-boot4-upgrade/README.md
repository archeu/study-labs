# 改个版本号，付款就 400 了：Spring Boot 3 到 4 升级

对应页面：[改个版本号，付款就 400 了：Spring Boot 3 到 4、Jakarta 与 Jackson 3 升级](https://archeu.github.io/study-notes/spring/spring-boot4-upgrade.html)

交易靶场的付款接口 `POST /orders/9527/pay`，同一份代码用三个 Maven profile 构建：

| profile | Boot | 说明 |
|---|---|---|
| `boot3`（默认） | 3.5.16 | 升级前：Jackson 2，`src/boot3/java` 里的 `JsonConfig` 用 `Jackson2ObjectMapperBuilderCustomizer` |
| `boot4-naive` | 4.1.2 | 只改版本号，代码和 MyBatis starter 都不动：看编译器报什么 |
| `boot4` | 4.1.2 | 按迁移指南改完：`starter-webmvc`、MyBatis starter 4.1.0、`src/boot4/java` 里的 `JsonMapperBuilderCustomizer`；另外留了一条 Jackson 2 依赖，模拟三方 SDK 把它带进来 |

`UpgradeLab` 启动应用，用 JDK HttpClient 带 Basic 头（1024:pw）发 6 个请求，打印状态码和响应体。

## 怎么跑

```bash
cd java
M="mvn -q -pl spring/spring-boot4-upgrade"
$M -Pboot3 clean compile exec:java -Dexec.mainClass=labs.spring.spring_boot4_upgrade.UpgradeLab
$M -Pboot4 clean compile exec:java -Dexec.mainClass=labs.spring.spring_boot4_upgrade.UpgradeLab
# Boot 4 + Jackson 2 兼容默认值
$M -Pboot4 exec:java -Dexec.mainClass=labs.spring.spring_boot4_upgrade.UpgradeLab -Dspring.jackson.use-jackson2-defaults=true
# Boot 4 + 只关掉枚举按 toString() 读写
$M -Pboot4 exec:java -Dexec.mainClass=labs.spring.spring_boot4_upgrade.UpgradeLab \
  -Dspring.jackson.datatype.enum.read-enums-using-to-string=false -Dspring.jackson.datatype.enum.write-enums-using-to-string=false
# 只改版本号：看编译错误
mvn -pl spring/spring-boot4-upgrade -Pboot4-naive clean compile
# MyBatis starter 不升：看启动失败和条件报告
$M -Pboot4 -Dmybatis-starter.version=3.0.5 clean compile exec:java -Dexec.mainClass=labs.spring.spring_boot4_upgrade.UpgradeLab -Ddebug=true
```

IDE 里在 Maven 面板勾选 profile（`boot3` 或 `boot4`）再刷新，`build-helper` 会把对应的 `src/boot3/java` 或 `src/boot4/java` 加成源码目录。

## 预期输出

本机：2 核 ARM Linux，OpenJDK 17.0.21，Maven 3.6.3。

| 请求 | Boot 3.5.16 | Boot 4.1.2 默认 | + use-jackson2-defaults | + 只关枚举两项 |
|---|---|---|---|---|
| 1 正常付款 | 200 | 400 Jackson（枚举只认“支付宝”） | 200 | 200 |
| 2 金额是 null | 400 Validator | 400 Jackson | 400 Validator | 400 Jackson（null 进 long） |
| 3 没传金额 | 400 Validator | 400 Jackson | 400 Validator | 400 Jackson |
| 4 多一个字段 | 200 | 400 Jackson | 200 | 200 |
| 5 尾巴多一段 | 200 | 400 Jackson | 200 | 400 Jackson（trailing token） |
| 6 渠道写中文名 | 400 Jackson | 200 | 400 Jackson | 400 Jackson |

200 时的 `payNo`：Boot 3 是字符串 `"1987654321098765432"`，Boot 4 三种都是数字（Jackson 2 的 `@JsonSerialize` 没人认）；`payNo2`（`@JsonFormat(shape = STRING)`）四种都是字符串。

`boot4-naive` 编译报 8 个错误（`JsonConfig.java` 6 个、`Model.java` 2 个）。MyBatis 3.0.5 跑在 Boot 4 上：`OrderMapper` bean 找不到，条件报告里 `MybatisAutoConfiguration` 因为 `@ConditionalOnSingleCandidate (types: javax.sql.DataSource)` 没匹配而跳过。

## 练习

先猜结果，再运行。

1. 在 `boot3` 下把 6 个请求的状态码和响应体录成快照，在 `boot4` 下逐字比对。猜：Boot 4 默认有几处差异？只关枚举两项以后还剩几处？
2. 把 `PayRequest.amountCents` 改成 `Long` 加 `@NotNull`。猜：请求 2、3 在两边分别被谁拦下？
3. 删掉 `boot4` profile 里那条 `jackson-databind`（Jackson 2）依赖。猜：编译器报哪几行？
