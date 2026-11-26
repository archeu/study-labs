# Java 框架：Spring AI Alibaba Graph（全景那张图的 Java 版）

对应页面：[Java 框架：Spring AI、Spring AI Alibaba、LangChain4j](https://study.ainiya.de/agent/java-agent-frameworks.html) 的第二个模块。

`GraphTriage.java`：spring-ai-alibaba-graph-core 1.1.2.3，不用 Spring Boot，节点里不调模型（sleep 模拟模型耗时）。
triage → logs / metrics / notes 并行 → summarize →（interruptBefore 停下）→ 批准 → act。

| LangGraph | Spring AI Alibaba Graph |
|---|---|
| `StateGraph(State)` | `new StateGraph(() -> Map<String, KeyStrategy>)` |
| reducer：`Annotated[list, operator.add]` | `AppendStrategy`；覆盖是 `ReplaceStrategy` |
| `Send` 扇出 | `addEdge("triage", List.of("logs", "metrics", "notes"))`，汇合 `addEdge(List.of(...), "summarize")` |
| `checkpointer=SqliteSaver(...)` | `CompileConfig.saverConfig(SaverConfig.builder().register(new MemorySaver()))` |
| `interrupt()` / `interrupt_before` | `CompileConfig.interruptBefore("act")` |
| `Command(resume=...)` | `updateState(cfg, Map.of("approval", ...))` 再用 `RunnableConfig.builder().threadId(...).resume()` invoke |

## 怎么跑

```bash
cd java
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
mvn -q -pl agent/java-agent-graph compile exec:java
```

## 预期输出

2026-11-26，2 核 ARM，OpenJDK 17：

```
  1.27s  [labs.agent.java_agent_graph.GraphTriage.main()] triage 分诊：派 3 个子 agent
  1.29s  [pool-1-thread-2] investigate[metrics] 开始
  1.29s  [pool-1-thread-3] investigate[notes] 开始
  1.29s  [pool-1-thread-1] investigate[logs] 开始
  1.99s  [pool-1-thread-3] investigate[notes] 完成
  2.19s  [pool-1-thread-2] investigate[metrics] 完成
  2.49s  [pool-1-thread-1] investigate[logs] 完成
  2.50s  [pool-1-thread-1] summarize 汇总 3 段结论
  2.51s  [labs.agent.java_agent_graph.GraphTriage.main()] ⏸ 停下：findings 3 段，下一步 = act
  2.51s  [labs.agent.java_agent_graph.GraphTriage.main()] act 执行：批准人 = 张三 20:31
  2.52s  [labs.agent.java_agent_graph.GraphTriage.main()] 结束：ticket = INC-1108，检查点 6 个
```

不传 `defaultParallelExecutor` 时，并行节点跑在框架自建的 `parallel-node-action-thread-N` 上，这些线程不是守护线程：图跑完了，`exec:java` 却一直不退出（本机等到 10 分钟超时）。

## 练习

1. 去掉 `defaultParallelExecutor(pool)` 和 `pool.shutdown()`，看进程会不会退出，用 `jstack` 看还剩哪些线程。
2. 把 `findings` 的 `AppendStrategy` 换成 `ReplaceStrategy`，summarize 看到几段？
