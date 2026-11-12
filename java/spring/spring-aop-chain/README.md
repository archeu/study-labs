# 代理里那十几层在干什么：AOP 与代理下一圈

对应页面：[代理里那十几层在干什么](https://archeu.github.io/study-notes/spring/spring-aop-chain.html)（Java 地图 J05 · 全景《一个请求穿过 Spring》第 6 站）

交易靶场·订单付款。不起 Web，H2 内存库，`OrderController` 是普通 bean，直接调它。每个场景单独起一个 Spring 容器，打印代理上的 Advisor 链、Controller 到 `OrderService` 之间夹的调用栈、计时切面进来时事务开没开。

| 类 | 作用 |
|---|---|
| `ChainLab` | 主程序：`chain` `plain` `stack` `timed` `self` `async` `order0` 七个场景，不带参数全跑 |
| `OrderService` | `pay`（只有事务）、`payTimed`（事务 + `@Timed`）、`payViaThis`（自调用） |
| `NotifyService` | `@Async @Transactional @Timed` 叠在一个方法上 |
| `TimingAspect` | `@Around` 计时切面；`--lab.timing-order=0` 排到事务外面，`--lab.timing=false` 关掉 |
| `ProxyBench` | 粗测：直接调用、空链代理、1/3/10 个拦截器、1 个 `@Around`，每次调用多少纳秒 |

## 怎么跑

```bash
cd java
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -q -pl spring/spring-aop-chain compile exec:java \
  -Dexec.mainClass=labs.spring.spring_aop_chain.ChainLab
# 只跑某几个场景
mvn -q -pl spring/spring-aop-chain exec:java -Dexec.mainClass=labs.spring.spring_aop_chain.ChainLab -Dexec.args="plain order0"
# 粗测开销（约 1 分钟）
mvn -q -pl spring/spring-aop-chain exec:java -Dexec.mainClass=labs.spring.spring_aop_chain.ProxyBench
```

## 预期输出（本机 2 核 ARM，OpenJDK 17.0.21，Spring Boot 3.5.16 / Spring 6.2.19）

| 场景 | 结果 |
|---|---|
| chain | 链：`ExposeInvocationInterceptor` → `TransactionInterceptor` → `AspectJAroundAdvice`；计时切面进来时有事务=true（没算提交） |
| plain | 没有计时切面，链上只有事务；热身 20 次后中间夹 11 层（反射是 `GeneratedMethodAccessor`） |
| stack | 同一个 `pay()`，容器里有计时切面，冷调用 14 层（多了 Expose 2 层、未膨胀的反射 1 层） |
| timed | `payTimed` 冷调用 24 层 |
| self | `payViaThis → this.pay()`：12 层，`事务已开启=false` |
| async | 链：`AsyncAnnotationAdvisor` 在第 0 位；调用方线程 main，方法在 task-1 里，`事务已开启=true` |
| order0 | 链：Expose → `AspectJAroundAdvice` → `TransactionInterceptor`；计时切面进来时有事务=false（算上了提交） |

`ProxyBench`（两遍相差 3% 以内）：直接 0.4 ns，空链 58 ns，+1 拦截器 128 ns，+3 个 135 ns，+10 个 161 ns，+1 个 `@Around` 206 ns。

## 练习

先猜结果，再运行。

1. 热身 50 次后，默认顺序和 `--lab.timing-order=0` 各量 1000 次 `payTimed`，取中位数。两者的差就是“开事务 + 提交”。
2. 写 `RetryAspect`（最多重试 3 次），`pay` 每次先往 `pay_log` 插一行、前两次抛异常。`@Order(0)` 时 `pay_log` 剩 1 行；不写 order 时 3 行。
3. 去掉 `NotifyService.onPaid` 上的 `@Async @Transactional`，改成发布 `OrderPaid` 事件 + `@Async @TransactionalEventListener`：`pay` 回滚时监听器不执行。
