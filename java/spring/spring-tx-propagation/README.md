# 付款失败，流水要不要一起撕：事务传播、隔离与失效下一圈

对应页面：[付款失败，流水要不要一起撕](https://archeu.github.io/study-notes/spring/spring-tx-propagation.html)（Java 地图 J05 · 全景《一个请求穿过 Spring》第 7、10 站）

交易靶场·订单付款。不起 Web，直接调 `PayService`。H2 内存库（MySQL 模式），HikariCP 故意只开 4 条连接、借不到等 1 秒（`application.properties`）。

| 类 | 作用 |
|---|---|
| `Lab` | 主程序：`matrix` `starve` `selflock` `iso` `timeout` `listener` `listener-noac` `validate` `bad-listener`，不带参数全跑 |
| `PayService` | `pay`（改订单 → 写流水 → 金额不符就抛）、`payThenLog`（连接池实验）、`payThenTouchOrderInNewTx`（自锁）、`isolationPeek`、`payWithTimeout` |
| `PayLogService` | 写流水：REQUIRED / REQUIRES_NEW / NESTED 各一个方法；隔离级别探针 |
| `NotifyListeners` | 提交后的监听器：`Plain`（同步，不加事务）、`RequiresNew`；`Bad`（挂普通 `@Transactional`，`lab.bad-listener=true` 时才注册） |
| `Conn` | 打印线程上绑的是哪条连接、隔离级别、当前事务名 |
| `TradeMapper` | MyBatis 注解 Mapper：orders、pay_log、notify_log |

## 怎么跑

```bash
cd java
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -q -pl spring/spring-tx-propagation compile exec:java \
  -Dexec.mainClass=labs.spring.spring_tx_propagation.Lab
# 只跑某几个场景
mvn -q -pl spring/spring-tx-propagation exec:java -Dexec.mainClass=labs.spring.spring_tx_propagation.Lab -Dexec.args="matrix starve"
```

`bad-listener` 场景会打一段 Spring 启动失败的堆栈，这是预期的。

## 预期输出（本机 2 核 ARM，OpenJDK 17.0.21，Spring Boot 3.5.16 / Spring 6.2.19，mybatis-spring-boot-starter 3.0.5）

`matrix`（订单状态 · 流水条数，调用方看到什么）：

| 情况 | REQUIRED | REQUIRES_NEW | NESTED |
|---|---|---|---|
| A 金额对，流水正常 | PAID · 1 | PAID · 1 | PAID · 1 |
| B 金额不符 | CREATED · 0（AmountMismatch） | CREATED · **1** | CREATED · 0 |
| C 流水失败，外层 catch | CREATED · 0（**UnexpectedRollbackException**） | PAID · 0 | PAID · 0 |
| D 流水失败，不 catch | CREATED · 0 | CREATED · 0 | CREATED · 0 |

| 场景 | 结果 |
|---|---|
| starve | 3 个并发：十几 ms 全成功；4 个并发 + REQUIRES_NEW：约 1015 ms 后全失败（`CannotCreateTransactionException ← SQLTransientConnectionException … total=4, active=4, idle=0`）；4 个并发 + NESTED：全成功 |
| selflock | 约 2.2 s 后 `CannotAcquireLockException`（H2：`is locked by tx 1 and can not be updated by tx 2`），订单 CREATED |
| iso | 外层 READ_COMMITTED；内层 REQUIRED + SERIALIZABLE 仍是 READ_COMMITTED（同一条连接）；内层 REQUIRES_NEW + SERIALIZABLE 是 SERIALIZABLE（另一条连接）；还回池子后再借是 READ_COMMITTED |
| timeout | `timeout = 1`：先睡 1.5 s 再 UPDATE → `TransactionTimedOutException`；先 UPDATE 再睡 → 提交成功 PAID |
| listener | plain 1 条、requires_new 1 条 |
| listener-noac | 池子 `auto-commit=false`：plain **0 条**、requires_new 1 条 |
| validate | `IllegalTransactionStateException: Participating transaction with definition [PROPAGATION_REQUIRED,ISOLATION_SERIALIZABLE] specifies isolation level which is incompatible …` |
| bad-listener | 启动失败：`@TransactionalEventListener method must not be annotated with @Transactional unless when declared as REQUIRES_NEW or NOT_SUPPORTED` |

## 练习

先猜结果，再运行。

1. 把 `maximum-pool-size` 改成 5，再跑 `starve`：4 个并发 + REQUIRES_NEW 还会失败吗？（公式 Tn × (Cm − 1) + 1）
2. 把“金额不符”的流水挪进 `@TransactionalEventListener(phase = AFTER_ROLLBACK)` + `@Async` + `@Transactional(REQUIRES_NEW)` 的监听器：B 情况流水几条？starve 还卡吗？
3. `application.properties` 加 `spring.datasource.hikari.auto-commit=false` 跑 `listener`，再给 `NotifyListeners.Plain` 加 `@Transactional(propagation = REQUIRES_NEW)` 跑一次；最后改成 REQUIRED，看启动报什么。
