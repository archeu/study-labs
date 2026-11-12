# 代理是哪一刻换进容器的：Bean 生命周期下一圈

对应页面：[代理是哪一刻换进容器的](https://archeu.github.io/study-notes/spring/spring-bean-proxy-swap.html)（Java 地图 J05 · 全景《一个请求穿过 Spring》第 1、6 站）

交易靶场·订单服务的启动装配。一个 JVM 里依次起九个 Spring 容器（不起 Web，H2 内存库），每个场景打印代理在哪一步换进去、启动成没成功、事务在不在。

| 类 | 场景 |
|---|---|
| `ProxySwapLab` | 主程序：按名字跑场景，打印结果 |
| `Basic` | `basic`：`SwapWatcher` 看 orderService 初始化前后的类；`@PostConstruct` 里调 `this.pay()`；`ApplicationReadyEvent` 里从注入的代理调 |
| `Cycles` | `cycle-banned` / `cycle-tx`：订单 ⇄ 库存；`async-first` / `async-second` / `async-via-controller`：通知（`@Async`）⇄ 订单，换建 bean 的顺序 |
| `EarlyBpp` | `early-bpp` / `early-bpp-priority`：BeanPostProcessor 构造注入了订单服务，普通组和 PriorityOrdered 组 |

## 怎么跑

```bash
cd java
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -q -pl spring/spring-bean-proxy-swap compile exec:java \
  -Dexec.mainClass=labs.spring.spring_bean_proxy_swap.ProxySwapLab
# 只跑某几个场景
mvn -q -pl spring/spring-bean-proxy-swap exec:java \
  -Dexec.mainClass=labs.spring.spring_bean_proxy_swap.ProxySwapLab -Dexec.args="basic async-first"
# 对照 Spring 6.1：换成 Boot 3.3
mvn -q -Dspring-boot.version=3.3.13 -pl spring/spring-bean-proxy-swap clean compile exec:java \
  -Dexec.mainClass=labs.spring.spring_bean_proxy_swap.ProxySwapLab -Dexec.args="async-first"
```

## 预期输出（本机 2 核 ARM，OpenJDK 17.0.21，Spring Boot 3.5.16 / Spring 6.2.19）

| 场景 | 结果 |
|---|---|
| basic | 初始化前 `OrderService`，初始化后 `Basic$OrderService$$SpringCGLIB$$0`；`@PostConstruct` 里 `事务已开启=false`；`ApplicationReadyEvent` 里 `事务已开启=true` |
| cycle-banned | 启动失败：`Requested bean is currently in creation` |
| cycle-tx | 启动成功；库存服务手里的 order 是代理，和容器里是同一个对象，事务在 |
| async-first | 6.2：一条 INFO `skipping it in mainline thread`，然后启动成功；**Boot 3.3 / Spring 6.1.21：raw version 启动失败** |
| async-second | 启动成功（6.1、6.2 都一样） |
| async-via-controller | 启动失败：`has been injected into other beans [orderService] in its raw version` |
| early-bpp | WARN `not eligible for getting processed by all BeanPostProcessors`，但仍是代理，事务在 |
| early-bpp-priority | 同一条 WARN；orderService 是原始类，`事务已开启=false` |

## 练习

1. 把 `Basic.OrderService.warmUp()` 挪到一个实现 `SmartInitializingSingleton` 的 bean 里，通过注入的 `OrderService` 调用。预期 `事务已开启=true`。
2. 在 `async-via-controller` 里拆环：订单服务 `publishEvent(new OrderPaid(...))`，通知服务用 `@Async @TransactionalEventListener` 接。预期不打开 allow-circular-references 也能启动。
3. 把 `EarlyBpp.FirstAuditBpp` 的构造参数改成 `ObjectProvider<OrderService>`。预期 WARN 消失，`early-bpp-priority` 的事务回来。
