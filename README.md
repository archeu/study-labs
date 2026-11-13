# study-labs

[学习笔记站](https://archeu.github.io/study-notes/)（[archeu/study-notes](https://github.com/archeu/study-notes)）配套的可运行代码：页面⑤快速入门的完整工程、页面上“实测”数字背后的实验，以及 ↺ 的改造练习。克隆下来用 IDE 打开就能跑、能改。

## 怎么用

| 线 | IDE 里打开 | 环境 | 跑一个实验 |
|---|---|---|---|
| Java 后端架构 | `java/pom.xml`（IDEA 按 Maven 项目导入） | JDK 17（个别模块标了 8 或 21），Maven 3.6+ | IDE 里直接运行 main；或 `cd java && mvn -q -pl <主题>/<名字> compile exec:java -Dexec.mainClass=<类名>` |
| AI Agent 工程师 | `agent/` 目录 | [uv](https://docs.astral.sh/uv/)，Python 3.13 由 uv 自动安装 | `cd agent && uv sync`，然后 `uv run python <名字>/main.py`、`uv run pytest <名字>/` |

## 约定

- **一页一个目录，名字和页面文件名一样。** 页面 `juc/threadpool-execute.html` 的代码在 `java/juc/threadpool-execute/`，页面 `agent/python-asyncio.html` 的代码在 `agent/python-asyncio/`。
- **双向链接。** 每个目录的 README 第一行链回页面；页面的⑤和“实测”处有“完整代码 →”链到这里。
- **每个目录的 README 写四件事：** 对应页面、怎么跑、预期输出（标明哪台机器、哪个 JDK/Python）、练习（来自页面 ↺，先猜结果再运行）。
- **只放代码。** 中间件数据、下载包、模型文件、日志、构建产物都不进仓库（见 `.gitignore`）；需要中间件的实验在 README 里写清楚怎么启动。
- **Java 包名** `labs.<主题>.<名字>`，名字里的 `-` 换成 `_`。**Python 测试文件**叫 `test_<名字>.py`，按目录运行。

## 旧代码

2026-11-09 之前各页面的实验代码按原样收在 [`legacy/`](legacy/)，按页面分目录，说明见 [legacy/README.md](legacy/README.md)。

## AI Agent 工程师

| 页面 | 代码 | 说明 |
|---|---|---|

## Java 后端架构

| 页面 | 代码 | 说明 |
|---|---|---|
| [全景：一个订单对象的一生](https://archeu.github.io/study-notes/java/pano-j01-order-object-life.html) | [`java/java/pano-j01-order-object-life`](java/java/pano-j01-order-object-life/) | `OrderApp`：Spring Boot 订单服务，每站打点；`/orders` 下单、`/stats` 看集合里攒了多少 |
| [面向对象、接口与抽象类、泛型和类型擦除](https://archeu.github.io/study-notes/javase/oop-generics-erasure.html) | [`java/javase/oop-generics-erasure`](java/javase/oop-generics-erasure/) | `Demo`：封装、多态、擦除、原始类型、桥方法、构造器陷阱 6 段，配 javap 命令 |
| [常用集合怎么选：ArrayList、LinkedList、Set、Queue](https://archeu.github.io/study-notes/javase/collections-choose.html) | [`java/javase/collections-choose`](java/javase/collections-choose/) | `OrderBook` 订单簿五种集合和四个坑；`Bench` 8 个场景粗测；`Memory` 每元素字节数 |
| [HashMap：哈希、扩容、树化](https://archeu.github.io/study-notes/javase/hashmap-internals.html) | [`java/javase/hashmap-internals`](java/javase/hashmap-internals/) | `Demo` + `Peek`：反射看桶数组，扩容、碰撞拆分、树化、坏 hashCode、并发写、可变 key 6 段 |
| [HashMap 的亲戚们：LinkedHashMap、WeakHashMap、ThreadLocalMap](https://archeu.github.io/study-notes/javase/hashmap-relatives.html) | [`java/javase/hashmap-relatives`](java/javase/hashmap-relatives/) | `Demo`：LRU、WeakHashMap 两个坑、ThreadLocalMap 格子、线程池串号和大对象 6 段 |
| [Lambda、Stream、record、模式匹配：编译后变成了什么](https://archeu.github.io/study-notes/javase/lambda-stream-record.html) | [`java/javase/lambda-stream-record`](java/javase/lambda-stream-record/) | `Demo`（JDK 17）：Lambda、Stream、parallel、装箱、record 5 段；`src/jdk21/PatternSwitch`（JDK 21）模式匹配 |
| [异常、反射、注解与动态代理：JDK 这一层](https://archeu.github.io/study-notes/javase/exception-reflection-proxy.html) | [`java/javase/exception-reflection-proxy`](java/javase/exception-reflection-proxy/) | `Demo` 5 段 + `TxAuditHandler` 手写事务审计代理；可存下生成的 `$Proxy` 类 javap |
| [IO 与 NIO 基础：流、Buffer、Channel](https://archeu.github.io/study-notes/javase/io-nio-basics.html) | [`java/javase/io-nio-basics`](java/javase/io-nio-basics/) | `Demo` 导出导入、字符集、ByteBuffer；`Copy` 五种复制法配 strace |
| [数据结构与算法：日常和面试够用的那部分](https://archeu.github.io/study-notes/javase/algorithms-everyday.html) | [`java/javase/algorithms-everyday`](java/javase/algorithms-everyday/) | `Demo`：Top 10、二分、滑动窗口限流、排序稳定性与比较器、TreeMap 红黑树结构 5 段 |
| [全景：一个下单请求在线程池里](https://archeu.github.io/study-notes/java/pano-j02-order-in-threadpool.html) | [`java/java/pano-j02-order-in-threadpool`](java/java/pano-j02-order-in-threadpool/) | `OrderApp --rush`：起服务后自己压测，1000 人抢 100 件，五种扣库存写法（mem、naive、sync、cas、db）各跑一轮，打印超卖件数、Tomcat 线程数、BLOCKED 线程数；可加 `--spring.threads.virtual.enabled=true` |
| [线程、线程状态与上下文切换](https://archeu.github.io/study-notes/juc/thread-state-context-switch.html) | [`java/juc/thread-state-context-switch`](java/juc/thread-state-context-switch/) | `States`：靶场线程各停在一种状态，对照 Java 状态和 /proc 内核状态；`CtxSwitch`：线程数与切换次数、等待型任务的线程数、park/unpark 交接耗时、新建线程 vs 线程池 |
| [Java 内存模型下一圈：jcstress、CPU 缓存与伪共享](https://archeu.github.io/study-notes/juc/jmm-jcstress-cache.html) | [`java/juc/jmm-jcstress-cache`](java/juc/jmm-jcstress-cache/) | jcstress 0.16 测试：`StockTests`（库存扣减 Plain / Volatile / Cas）、`ReorderTests`（SB、MP、volatile 计数）；`FalseSharing`：伪共享对比 |
| [synchronized 与锁升级、CAS 与原子类](https://archeu.github.io/study-notes/juc/synchronized-cas-atomic.html) | [`java/juc/synchronized-cas-atomic`](java/juc/synchronized-cas-atomic/) | `MarkWord`（JOL 看对象头和锁状态）、`SyncBytecode`（javap）、`Aba`（AtomicStampedReference）、`Bench`（五种写法吞吐） |
| [AQS 下一圈：读写锁、StampedLock、Semaphore 与 JFR 找锁竞争](https://archeu.github.io/study-notes/juc/aqs-rwlock-jfr.html) | [`java/juc/aqs-rwlock-jfr`](java/juc/aqs-rwlock-jfr/) | `RwBench`（四把锁读多写少吞吐）、`SlowLock`（锁里慢 SQL 配 JFR 录制、Semaphore 舱壁） |
| [ConcurrentHashMap 与阻塞队列](https://archeu.github.io/study-notes/juc/chm-blocking-queue.html) | [`java/juc/chm-blocking-queue`](java/juc/chm-blocking-queue/) | `MapRace`（HashMap 丢数据、get 再 put vs merge）、`ChmPeek`（反射看 table、TreeBin、计数格）、`QueueDemo`（有界队列、DelayQueue、ABQ vs LBQ） |
| [线程池下一圈：怎么配、拒绝策略、动态调参与监控](https://archeu.github.io/study-notes/juc/threadpool-tuning.html) | [`java/juc/threadpool-tuning`](java/juc/threadpool-tuning/) | `Overload`（四种拒绝策略过载实测）、`LiveResize`（运行中调大池子、监控、优雅关闭） |
| [ThreadLocal 与内存泄漏](https://archeu.github.io/study-notes/juc/threadlocal-leak.html) | [`java/juc/threadlocal-leak`](java/juc/threadlocal-leak/) || `Mixup`（不 remove 导致串号）、`Leak`（反射数 ThreadLocalMap，看 key=null 后还挂着多少 MB） |
| [CompletableFuture 与异步编排](https://archeu.github.io/study-notes/juc/completablefuture-orchestration.html) | [`java/juc/completablefuture-orchestration`](java/juc/completablefuture-orchestration/) || `WhichThread`（回调跑在哪个线程、默认池塞阻塞任务）、`Notify`（allOf 并行、异常、超时兜底、ThreadLocal 传递） |
| [虚拟线程：什么时候用、pinning 的坑](https://archeu.github.io/study-notes/juc/virtual-threads-pinning.html) | [`java/juc/virtual-threads-pinning`](java/juc/virtual-threads-pinning/) || `Throughput`（等 IO vs 计算，平台线程池 vs 虚拟线程）、`Pinning`（synchronized 钉住 vs ReentrantLock，配 trace 参数和 JFR） |
| [全景：一个对象从 new 到被回收](https://archeu.github.io/study-notes/java/pano-j03-object-new-to-gc.html) | [`java/java/pano-j03-object-new-to-gc`](java/java/pano-j03-object-new-to-gc/) || `ObjLife`（订单对象在 G1 上走完十站，最后泄漏到 OOM）、`Order`（48 字节） |
| [new 一个对象的全过程：分配、对象头与内存布局](https://archeu.github.io/study-notes/jvm/jvm-object-new-layout.html) | [`java/jvm/jvm-object-new-layout`](java/jvm/jvm-object-new-layout/) | `Layout`（JOL 看 Order 48 字节、关压缩指针 56）、`AllocCost`（开 / 关 TLAB 计时） |
| [GC 日志与调优：先定目标，再动参数](https://archeu.github.io/study-notes/jvm/jvm-gc-log-tuning.html) | [`java/jvm/jvm-gc-log-tuning`](java/jvm/jvm-gc-log-tuning/) | `OrderService`（大促 / 泄漏压测，按计划到达时刻算延迟）、`GcLogStats`（从 GC 日志算占比、最长停顿、回收后占用并下结论） |
| [类加载与双亲委派：SPI 为什么要打破它](https://archeu.github.io/study-notes/jvm/jvm-classloading.html) | [`java/jvm/jvm-classloading`](java/jvm/jvm-classloading/) | `WhoLoads` 加载器链、`TwoOrders` 两个 Order 互转、`SpiDemo` 支付渠道 SPI 与 TCCL、`LoaderLeak` 元空间泄漏 |
| [JIT、逃逸分析与常见的性能误区](https://archeu.github.io/study-notes/jvm/jvm-jit-escape.html) | [`java/jvm/jvm-jit-escape`](java/jvm/jvm-jit-escape/) | `Warmup`（解释 → C1 → C2 预热曲线，配 PrintCompilation）、`Escape`（8 种写法进不进堆）、`Myths`（微基准被删、热点 NPE 丢堆栈） |
| [JVM 故障排查：OOM、频繁 Full GC、CPU 飙高](https://archeu.github.io/study-notes/jvm/jvm-troubleshooting.html) | [`java/jvm/jvm-troubleshooting`](java/jvm/jvm-troubleshooting/) | `TradeServer`（三个雷：泄漏到 OOM、大导出频繁 Full GC、一分钱优惠券死循环，各带改法）、`Load`（压测客户端） |
| [容器里的 JVM：内存和 CPU 限制](https://archeu.github.io/study-notes/jvm/jvm-container.html) | [`java/jvm/jvm-container`](java/jvm/jvm-container/) | `WhatJvmSees` 打印 JVM 在 cgroup 里看到的 CPU、最大堆、GC；`OffHeapCreep` 在 512 MB 里涨直接内存，复现 137 和封顶后的 OOM |
| [全景：订单服务调库存服务的一次 RPC](https://archeu.github.io/study-notes/java/pano-j04-order-calls-inventory.html) | [`java/java/pano-j04-order-calls-inventory`](java/java/pano-j04-order-calls-inventory/) | `OrderCallsInventory`：两个 gRPC 库存实例 + HTTP/1.1 对照，测序列化大小、首次与稳定耗时、并发连接数、HTTP/2 帧、deadline |
| [TCP：握手、挥手、TIME_WAIT 与粘包](https://archeu.github.io/study-notes/network/tcp-handshake-timewait-framing.html) | [`java/network/tcp-handshake-timewait-framing`](java/network/tcp-handshake-timewait-framing/) | `Framing`（粘包、拆包、长度前缀）、`NagleDelayedAck`（40 ms 对比）、`TimeWait`（client / server / linger0 / leak 四种关法，用 ss 数状态） |
| [Netty：EventLoop、Pipeline、ByteBuf](https://archeu.github.io/study-notes/network/netty-eventloop-pipeline-bytebuf.html) | [`java/network/netty-eventloop-pipeline-bytebuf`](java/network/netty-eventloop-pipeline-bytebuf/) | `InventoryNetty`（⑤ 库存预占服务 + 客户端）、`BlockingEventLoop`（慢处理器堵 EventLoop，inline / offload）、`LeakDemo`（ByteBuf 不 release，leak / fixed）；Netty 4.2.19 |
| [RPC 原理：序列化、协议、服务发现、负载均衡](https://archeu.github.io/study-notes/network/rpc-serialization-discovery-lb.html) | [`java/network/rpc-serialization-discovery-lb`](java/network/rpc-serialization-discovery-lb/) | `MiniRpcDemo`：纯 JDK 写的迷你 RPC（动态代理 Stub、17 字节帧头、JDK 对比 Compact 序列化、内存注册中心、轮询 / 随机 / 最少活跃），七段演示含慢实例、坏实例和超时；JDK 8 写法 |
| [HTTP 客户端与连接池：超时怎么配](https://archeu.github.io/study-notes/network/http-client-pool-timeouts.html) | [`java/network/http-client-pool-timeouts`](java/network/http-client-pool-timeouts/) | Apache HttpClient 5.6.5：`InventoryClient`（四段超时 + 总 deadline）、`TimeoutLab`（读超时不是总时长、池子排队、超时重试扣两次）、`FakeInventoryServer` |
| [全景：一个请求穿过 Spring](https://archeu.github.io/study-notes/java/pano-j05-request-through-spring.html) | [`java/java/pano-j05-request-through-spring`](java/java/pano-j05-request-through-spring/) | `RequestThroughSpring`：Spring Boot 3.5.16 + Security + MyBatis + H2，付款请求走十一站，打印各站痕迹、SQL 发出时的调用栈、分段耗时，四组对照（401、400、回滚、自调用）；`-Dlab.fast-auth=true` 去掉 BCrypt |
| [代理是哪一刻换进容器的：Bean 生命周期下一圈](https://archeu.github.io/study-notes/spring/spring-bean-proxy-swap.html) | [`java/spring/spring-bean-proxy-swap`](java/spring/spring-bean-proxy-swap/) | `ProxySwapLab`：Spring Boot 3.5.16 + H2，九个启动场景（@PostConstruct 里调事务方法、订单 ⇄ 库存环、@Async 环换建 bean 顺序、BPP 依赖业务 bean），`-Dspring-boot.version=3.3.13` 对照 Spring 6.1 |
| [249 个 bean 是谁建的：Boot 自动配置与启动下一圈](https://archeu.github.io/study-notes/spring/spring-boot-bean-ledger.html) | [`java/spring/spring-boot-bean-ledger`](java/spring/spring-boot-bean-ledger/) | `LedgerLab`：交易靶场订单服务，Spring Boot 3.5.16 + H2；记两道筛子（158→43→35）、249 个 bean 分组、启动步骤计时；`run-scenarios.sh` 跑八个场景（自建连接池、排除数据源、懒加载、Jackson 命名等） |
| [代理里那十几层在干什么：AOP 与代理下一圈](https://archeu.github.io/study-notes/spring/spring-aop-chain.html) | [`java/spring/spring-aop-chain`](java/spring/spring-aop-chain/) | `ChainLab` 七个场景（链、栈、排序、@Async、自调用），`ProxyBench` 每层开销粗测 |
| [付款失败，流水要不要一起撕：事务传播、隔离与失效下一圈](https://archeu.github.io/study-notes/spring/spring-tx-propagation.html) | [`java/spring/spring-tx-propagation`](java/spring/spring-tx-propagation/) | `Lab`：交易靶场付款，Spring Boot 3.5.16 + MyBatis + H2，HikariCP 4 条连接；3 种传播 × 4 种情况，加 starve、selflock、iso、timeout、listener、listener-noac、validate、bad-listener 八个场景 |
| [100 笔订单发出 101 条 SQL：MyBatis 结果映射、N+1 与流式查询](https://archeu.github.io/study-notes/mybatis/mybatis-result-mapping.html) | [`java/mybatis/mybatis-result-mapping`](java/mybatis/mybatis-result-mapping/) | `Lab`：nplus1、lazy、join、auto、limit、handler、stream、pages 八个场景；`SqlCounter` 插件数 SQL 和借连接次数 |
| [一个 400 进了两次 DispatcherServlet](https://archeu.github.io/study-notes/spring/spring-mvc-dispatch.html) | [`java/spring/spring-mvc-dispatch`](java/spring/spring-mvc-dispatch/) | `DispatchLab`：十个场景看 REQUEST / ERROR 两次分派里谁出场，加一组冷热计时 |
| [付款一次算一遍密码：Spring Security 认证与授权下一圈](https://archeu.github.io/study-notes/spring/spring-security-token.html) | [`java/spring/spring-security-token`](java/spring/spring-security-token/) | `TokenLab`：十三个场景看 Basic、会话、JWT 三种凭证怎么认人，越权、篡改、过期、登出、会话固定，加三种凭证的耗时和 BCrypt cost 表 |
| [改个版本号，付款就 400 了：Spring Boot 3 到 4 升级](https://archeu.github.io/study-notes/spring/spring-boot4-upgrade.html) | [`java/spring/spring-boot4-upgrade`](java/spring/spring-boot4-upgrade/) | `UpgradeLab`：同一个付款接口用 `boot3` / `boot4-naive` / `boot4` 三个 profile 构建，发 6 个请求对照 Jackson 2 和 3 |
| [测试全绿，付款照样没回滚：JUnit 5、Mockito 与 Testcontainers](https://archeu.github.io/study-notes/spring/spring-testing.html) | [`java/spring/spring-testing`](java/spring/spring-testing/) | 没有 main，全靠 `mvn test`：`PaymentServiceTest`（JUnit 5 + Mockito）、`OrderControllerWebMvcTest`、`OrderMapperMySqlTest` / `OrderMapperH2Test`、`PaymentTxMySqlTest`（三颗事务雷）、`TransactionalTestTrapTest`；Testcontainers 起 MySQL 8.4，需要 Docker |
| [全景：一条 UPDATE 从应用到磁盘](https://archeu.github.io/study-notes/java/pano-j06-update-to-disk.html) | [`java/java/pano-j06-update-to-disk`](java/java/pano-j06-update-to-disk/) | `run.sh` 起 MySQL 8.4 一主一从并追踪；`UpdateToDisk`（setup / trace / bench）：执行计划、冷读页数、行锁、MVCC、redo LSN、binlog 事件、提交耗时、从库回放、脏页 |
| [100 万行里找 9527，只读了 3 页：索引与 B+ 树](https://archeu.github.io/study-notes/database/mysql-index-bplustree.html) | [`java/database/mysql-index-bplustree`](java/database/mysql-index-bplustree/) | `IndexLab`：setup 灌 100 万行、shape 读 orders.ibd 数每层页数、reads 数 7 条查询的拿页次数、prefix 看联合索引最左前缀；`run.sh` 起 MySQL 8.4 容器 |
| [明明有索引，它偏不走：执行计划与 SQL 调优](https://archeu.github.io/study-notes/database/mysql-explain-tuning.html) | [`java/database/mysql-explain-tuning`](java/database/mysql-explain-tuning/) | `PlanLab` 六段：setup、env、slow（慢日志 + 指纹汇总）、fix（三条慢 SQL 改前改后）、bench（16 种写法）、cross（回表分界点 + optimizer_trace）；`run.sh` 起 MySQL 8.4 容器 |
| [两次付款同时到，第二次改了 0 行：事务、MVCC 与锁（下一圈）](https://archeu.github.io/study-notes/database/mysql-lock-wait-deadlock.html) | [`java/database/mysql-lock-wait-deadlock`](java/database/mysql-lock-wait-deadlock/) | `LockLab`（`run.sh` 起 MySQL 8.4 容器）：连点两次付款三种写法、三种 WHERE 在 RR / RC 下锁了多少、1205 半截提交、死锁日志 |
| [commit 返回了，数据页还是旧的：redo、undo、binlog 与两阶段提交](https://archeu.github.io/study-notes/database/mysql-redo-undo-binlog.html) | [`java/database/mysql-redo-undo-binlog`](java/database/mysql-redo-undo-binlog/) | `LogLab`（see / purge / redo）+ `crash.sh`（提交途中 kill -9 三次），`run.sh` 起 MySQL 8.4 容器 |
