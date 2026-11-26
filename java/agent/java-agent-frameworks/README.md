# Java 框架：Spring AI、Spring AI Alibaba、LangChain4j

对应页面：[Java 框架：Spring AI、Spring AI Alibaba、LangChain4j](https://study.ainiya.de/agent/java-agent-frameworks.html)

站 04 的排障 agent 用 Java 写两遍。Spring AI Alibaba Graph 的那一份在隔壁模块 [`java-agent-graph`](../java-agent-graph/)（它依赖 Spring AI 1.1.x，和这里的 2.1 放不到一起）。

| 文件 | 是什么 |
|---|---|
| `OncallTools.java` | 三个只读工具，每个方法同时带 Spring AI 的 `@Tool`/`@ToolParam` 和 LangChain4j 的 `@Tool`/`@P`；现场数据和 Python 版 `oncall.py` 一样 |
| `SpringAiTriage.java` | Spring Boot 4.2.0 + Spring AI 2.1.0：`ChatClient` + `defaultTools` + Advisor 链；`RoundLogger` 排在 `ToolCallingAdvisor` 里面，每一轮模型调用记一行；参数 `internal` 不显式挂 `ToolCallingAdvisor` |
| `Lc4jTriage.java` | LangChain4j 1.24.0：接口 + `AiServices`，不需要 Spring；`ChatModelListener` 数每一轮，单独取缓存命中的 token |
| `application.properties` | Anthropic 模块指向本机 llama-server 的 `/v1/messages` |

`pom.xml` 里开了 `maven.compiler.parameters`：不开的话，工具参数名会变成 `arg0`、`arg1`（页面 ⑥ 坑 1）。

## 怎么跑

```bash
# 本机模型（llama.cpp b12618，Qwen3-1.7B Q4_K_M；--reasoning-budget 0 关掉思考）
llama-server -m Qwen3-1.7B-Q4_K_M.gguf --jinja -c 16384 --port 8090 -a qwen3-1.7b --reasoning-budget 0

cd java/agent/java-agent-frameworks
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
mvn -q spring-boot:run -Dspring-boot.run.main-class=labs.agent.java_agent_frameworks.SpringAiTriage
mvn -q spring-boot:run -Dspring-boot.run.main-class=labs.agent.java_agent_frameworks.SpringAiTriage -Dspring-boot.run.arguments=internal
mvn -q spring-boot:run -Dspring-boot.run.main-class=labs.agent.java_agent_frameworks.Lc4jTriage
# 连 Claude：Spring AI 改 application.properties（去掉 base-url、换 api-key 和 model）；LangChain4j 设 ANTHROPIC_BASE_URL、ANTHROPIC_API_KEY、MODEL
```

## 预期输出

2026-11-26，2 核 ARM，OpenJDK 17，Qwen3-1.7B（小模型有随机性）：

```
挂 ToolCallingAdvisor：
  第 1 轮  15.0s  输入 603 输出 37 token  要了 [searchLogs{"keyword":"P99","since":"20:00"}]
    （原始 usage：Usage{… cacheReadInputTokens=504, … inputTokens=99, outputTokens=37 …}）
    [tool] searchLogs(P99, 20:00) → 7 字
  第 2 轮   5.9s  输入 653 输出 31 token  要了 [parseGcLog{"since":"20:00"}]
    [tool] parseGcLog(20:00) → 92 字
  第 3 轮  34.4s  输入 751 输出 247 token  要了 []
工具调用 2 次，累计输入 2007 输出 315 token，用时 55.3s
```

- 不显式挂 `ToolCallingAdvisor`（`internal`）结果一样，`RoundLogger` 照样看到 3 轮：Spring AI 2.x 的 `ChatClient` 会自动注册一个（字节码里的 `autoRegisterToolCallingAdvisor`），而且只允许有一个（`validateSingleToolAdvisor`）。
- LangChain4j 走的路一样（3 轮、2 个工具，43.7～55.6s），但 `inputTokenCount()` 只是没命中缓存的部分：累计 148，另有缓存命中 1869；Spring AI 的 `getPromptTokens()` 把命中的加回去了（99 + 504 = 603）。
- 不开 `-parameters` 时第 1 轮要的是 `searchLogs{"arg0":"20:00","arg1":"20:16"}`：时间填进了关键字，什么都没查到。

## 练习

先猜结果，再运行。

1. 去掉 pom 里的 `maven.compiler.parameters`，`mvn clean` 后再跑 Spring AI 版，第 1 轮要的工具参数长什么样？LangChain4j 版呢？
2. 把 `RoundLogger` 的 `getOrder()` 改成 `ToolCallingAdvisor.DEFAULT_ORDER - 1`（排到外面），它还能看到几轮？
3. 给 Spring AI 版加一个 `MessageChatMemoryAdvisor`，连问两次，第二次的输入 token 多了多少？
