# 旧代码（2026-11-09 之前）

建这个仓库之前，各页面的实验代码散在临时目录里。这里按原样收进来，按页面分目录，**没有改造成 Maven 模块或统一的 uv 项目**，也没有补 README。新页面的代码在仓库根下的 `java/`、`agent/`，规范见根 README。

收录范围：页面⑤的案例、“实测”数字背后的实验、↺ 练习的参考解，以及当时的探针代码。没收：拼页面用的脚本，以及从 JDK、HikariCP 原样拷来读源码用的副本。`lab/CountingBag.java`、`lab/ExBag.java` 是改过的 HikariCP 源码，保留了原 Apache 2.0 许可头。

## 怎么跑

- **没有依赖的 Java 文件**：JDK 11+ 直接 `java Xxx.java`；同目录多个类互相引用的（比如 `MiniDualQueue` + `MiniTest`），先 `javac -d out *.java` 再 `java -cp out MiniTest`。2026-11-09 用 JDK 21 编译过下表标 ✓ 的目录，全部通过。
- **用到虚拟线程的**（`HandoffBenchVT`、`Pin`、`IoServer`、`Load*`、`lab/Bench`）要 JDK 21。`j8/` 子目录是给 JDK 8 跑的版本。
- **要第三方 jar 的**：Kafka 实验要 `kafka-clients`，Hikari 实验要 `HikariCP`（加 `slf4j-api`），用 `java -cp <jar>:. Xxx.java` 运行；Kafka 还要先在本地起 broker（代码里写的是 `localhost:19092`）。
- **Python**：进目录后 `uv sync`，再 `uv run python xxx.py` / `uv run pytest`。

| 页面 | 目录 | 内容 | JDK 21 编译 |
|---|---|---|---|
| [SynchronousQueue 与 LinkedTransferQueue](https://archeu.github.io/study-notes/juc/synchronous-transfer-queue.html) | [java/juc/synchronous-transfer-queue](java/juc/synchronous-transfer-queue/) | 手写双队列 MiniDualQueue、自旋实验 spin/、交接基准（平台线程 / 虚拟线程）、SqPeek / PoolPeek 探针 | ✓ |
| [ThreadPoolExecutor.execute 源码](https://archeu.github.io/study-notes/juc/threadpool-execute.html) | [java/juc/threadpool-execute](java/juc/threadpool-execute/) | TpePeek 看 ctl 和 Worker、TpePits 几个坑、练习 Ex2 | ✓ |
| [AQS 源码](https://archeu.github.io/study-notes/juc/aqs.html) | [java/juc/aqs](java/juc/aqs/) | AqsPeek 看队列、LockBench 公平 / 非公平、Herd、Stuck、Pin（虚拟线程钉住）；j8/ 为 JDK 8 版 | ✓ |
| [HikariCP ConcurrentBag 源码](https://archeu.github.io/study-notes/database/hikari-concurrentbag.html) | [java/database/hikari-concurrentbag](java/database/hikari-concurrentbag/) | BagPeek、WeakCheck；lab/ 下带路径计数的 CountingBag 和基准 | 要 HikariCP jar |
| [响应时间越压越长](https://archeu.github.io/study-notes/perf/response-time-queueing.html) | [java/perf/response-time-queueing](java/perf/response-time-queueing/) | rtdemo（Spring Boot 被压服务）、Load / LoadT / LoadV 压测客户端、m.py / delta.py 读 actuator 指标 | ✓（rtdemo 用 Maven） |
| [三高知识地图](https://archeu.github.io/study-notes/arch/three-highs-map.html) | [java/arch/three-highs-map](java/arch/three-highs-map/) | Avalanche 雪崩演示 | ✓ |
| [G1 和 ZGC 怎么回收](https://archeu.github.io/study-notes/jvm/g1-zgc.html) | [java/jvm/g1-zgc](java/jvm/g1-zgc/) | GcDemo | ✓ |
| [JVM 内存结构](https://archeu.github.io/study-notes/jvm/jvm-memory-structure.html) | [java/jvm/jvm-memory-structure](java/jvm/jvm-memory-structure/) | MemDemo：内存池和各种溢出 | ✓ |
| [Java 内存模型](https://archeu.github.io/study-notes/juc/java-memory-model.html) | [java/juc/java-memory-model](java/juc/java-memory-model/) | JmmDemo：停止标志、store buffering | ✓ |
| [网络 IO 模型](https://archeu.github.io/study-notes/network/io-models.html) | [java/network/io-models](java/network/io-models/) | IoServer / IoClient：1 万连接 BIO / NIO / 虚拟线程 | ✓ |
| [从 URL 到页面](https://archeu.github.io/study-notes/network/url-to-page.html) | [java/network/url-to-page](java/network/url-to-page/) | MiniBrowser（及不开 TCP_NODELAY 的 MiniBrowserND）、Naive、Probe、练习 1 参考解 KeepAlive | ✓ |
| [Kafka 可靠性与消费组](https://archeu.github.io/study-notes/kafka/kafka-reliability-group.html) | [java/kafka/kafka-reliability-group](java/kafka/kafka-reliability-group/) | Exp、Idem（幂等）、Defaults（读客户端默认值） | 要 kafka-clients jar |
| 站 01 的 Python 页（[集合](https://archeu.github.io/study-notes/agent/python-collections.html)、[闭包和装饰器](https://archeu.github.io/study-notes/agent/python-closure-decorator.html)、[Pydantic](https://archeu.github.io/study-notes/agent/python-pydantic.html)、[asyncio](https://archeu.github.io/study-notes/agent/python-asyncio.html)、[FastAPI](https://archeu.github.io/study-notes/agent/python-fastapi.html)、[pytest](https://archeu.github.io/study-notes/agent/python-pytest.html)） | [agent/s01-gc-helper](agent/s01-gc-helper/) | 站 01 的靶场 gc-helper：summary / pits*（集合）、deco_demo / fa_pits*（装饰器）、models / pyd_*（Pydantic）、aio_*（asyncio）、main / fa_* / svc2（FastAPI）、tests/（pytest）；先 `uv run python make_logs.py` 生成日志 | — |
| [uv 管环境和依赖](https://archeu.github.io/study-notes/agent/python-uv.html) | [agent/python-uv](agent/python-uv/) | uv 初始化的 gc-helper 雏形和 fakehosts 假日志代理 | — |
| [一句话怎么变成模型的回答](https://archeu.github.io/study-notes/agent/pano-02-prompt-to-answer.html) | [agent/pano-02-prompt-to-answer](agent/pano-02-prompt-to-answer/) | llm-exp.py：对本地 llama.cpp 服务测 logprobs、预填充 / 解码耗时 | — |
