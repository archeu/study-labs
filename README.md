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
