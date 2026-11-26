# 写 MCP 服务端：Python 和 Java 各一个（Java 部分）

对应页面：[写 MCP 服务端：Python 和 Java 各一个](https://study.ainiya.de/agent/mcp-server-build.html)　Python 部分在 [`agent/mcp-server-build`](../../../agent/mcp-server-build/)

tickets 工单 MCP 服务端：Spring Boot 4.2.0 + Spring AI 2.1.0（`spring-ai-starter-mcp-server-webmvc`，底下是 MCP Java SDK 2.2.0），JDK 17。

| 文件 | 是什么 |
|---|---|
| `TicketsServer.java` | Spring Boot 启动类 |
| `TicketTools.java` | 两个 `@McpTool`：`get_incident`（只读，返回 record + `generateOutputSchema`）、`add_comment`（会改工单） |
| `application.properties` | 名字、版本、instructions；`spring.ai.mcp.server.protocol=MODERN`（2026-07-28）；绑 127.0.0.1:8766 |
| `pom.xml` | **maven-compiler-plugin 开了 `<parameters>true</parameters>`**：没有它，工具参数在 schema 里叫 arg0、arg1 |

## 怎么跑

```bash
cd java
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -q -pl agent/mcp-server-build spring-boot:run
# 老客户端也要能连时：
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -q -pl agent/mcp-server-build spring-boot:run \
  -Dspring-boot.run.arguments=--spring.ai.mcp.server.protocol=STREAMABLE
# 接进 Claude Code（只连一下）
claude mcp add -s local --transport http tickets http://127.0.0.1:8766/mcp && claude mcp get tickets
claude mcp remove -s local tickets
```

## 预期输出

2026-11-26，2 vCPU ARM，OpenJDK 17：

```
McpServerModernAutoConfiguration : Registered modern tools: 2
HmacRequestStateCodec  : no key configured, generated a random per-process key. State sealed by this instance
                         cannot be opened by another instance; configure a shared key for multi-instance deployments.
TicketsServer          : Started TicketsServer in 3.146 seconds     （常驻约 183 MB）
```

协议模式实测：`MODERN` 时老客户端（`initialize`）回 400 “Missing required header: Mcp-Method”；`STREAMABLE` 时老客户端能连，Python SDK 自动模式和 Claude Code 回退到 2025-11-25 也能连。

## 练习

1. 在 `getIncident` 里模拟底层异常 `throw new IllegalStateException("jdbc:mysql://10.0.3.7 timeout")`，看客户端收到什么；改成只回人话、细节写日志。
2. 加 `@McpResource(uri = "tickets://incident/{id}")`，用 Python 客户端 `read_resource` 读。
3. 多实例时配 `spring.ai.mcp.server.modern.request-state-secret`，让警告消失。
