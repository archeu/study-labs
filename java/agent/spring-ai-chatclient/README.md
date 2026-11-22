# Java 版：用 Spring AI ChatClient 写同一个服务

对应页面：[Java 版：用 Spring AI ChatClient 写同一个服务](https://study.ainiya.de/agent/spring-ai-chatclient.html)

Spring Boot 4.2.0 + Spring AI 2.1.0，JDK 17。`TriageApp` 是一个 `CommandLineRunner`：普通调用看用量、`entity(Triage.class)` 结构化（取值不合规再要一次，最多 3 次）、`stream().content()` 流式。两个模型 starter 都在类路径上，用 `spring.ai.model.chat` 选。

- `application.properties`：Anthropic 模块，`base-url` 指向本机 llama-server 的 `/v1/messages`（Qwen2.5-0.5B，8090）。
- `application-openai.yml`：OpenAI 模块，指向本机第二个 llama-server（Qwen3-0.6B，8091，启动时加 `--reasoning-budget 0`）。

## 怎么跑

```bash
cd java/agent/spring-ai-chatclient
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
mvn -q spring-boot:run                                         # Anthropic 模块
mvn -q clean spring-boot:run -Dspring-boot.run.profiles=openai  # OpenAI 模块，代码不变
# 连真正的 Claude：删掉 base-url，api-key=${ANTHROPIC_API_KEY}，chat.model=claude-sonnet-5-5
```

## 预期输出

2026-11-22，2 核 ARM：

```
（默认）
1) 文字：GC   用量：DefaultUsage{promptTokens=118, completionTokens=2, ...}，3451 ms
2) 第 1 次 entity()：Triage[type=GC, level=P3, ...]，校验通过
3) 流式：33 段，首段 526 ms，共 1920 ms
（openai）
1) 文字：好的，用户给了一个告警信息……（推理过程写进了回答，175 个 token）
2) 第 1/2/3 次 entity() 解析失败：StreamReadException
3) 流式：196 段，首段 544 ms
```

踩过的两个坑：`spring.ai.openai.chat.extra-body.chat_template_kwargs.enable_thinking=false` 会被当成字符串 `"false"` 透传，服务端 400（YAML 也一样）；删掉旧的资源文件后要 `mvn clean`，否则 `target/classes` 里的旧文件还在生效。

## 练习

1. 用 `OpenAiChatOptions` 在代码里设置布尔类型的 `extraBody`，看 400 是否消失。
2. 同时装配两家，声明两个 `ChatClient` Bean，主的失败就用备的。
3. 给 `entity()` 加上原生结构化输出的 advisor，openai profile 下再跑。
