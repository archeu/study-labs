# 结构化输出：JSON Schema、Pydantic 校验、失败重试

对应页面：[结构化输出：JSON Schema、Pydantic 校验、失败重试](https://study.ainiya.de/agent/api-structured-output.html)

站 03 地图过关题 1：连续调用 100 次，结构化结果能不能全部通过校验。两组各 100 次，同一个 Pydantic 模型 `Triage` 判卷。

- A `prompt`：只靠提示词要 JSON（官方 anthropic SDK → 本机 `/v1/messages`，它不认 `output_config.format`）；Pydantic 校验，不合格带着错误原因重试，最多 3 次。
- B `schema`：`Triage.model_json_schema()` 交给本机 OpenAI 兼容接口的 `response_format`，llama.cpp 做约束解码。

## 怎么跑

先按 [llm-next-token 的 README](../llm-next-token/README.md) 起好 8090 端口的服务。

```bash
cd agent && uv sync
uv run python api-structured-output/main.py both     # 约 9 分钟；N=10 可以先跑个小的
```

## 预期输出

2026-11-22，anthropic 2.12.0 / llama.cpp b12618，Qwen2.5-0.5B-Instruct Q4_K_M，温度 0.8（服务端默认）：

```
A 只靠提示词：100 次里 第一次就合格 98，重试后合格 99，类别正确 58；一共调用 103 次，用时 259 s
   第几次合格：{1: 98, 2: 1, '3 次都失败': 1}
   失败原因：{'缺字段': 3, '没有 JSON': 1}
B JSON Schema 约束：100 次里 第一次就合格 100，类别正确 62；用时 251 s
```

连 Claude 时，B 组的等价写法是 `client.messages.parse(..., output_format=Triage).parsed_output`。

## 练习

1. A 组里加上“`stop_reason` 不是 `end_turn` 就不重试”，`max_tokens` 改成 30 跑 10 次。
2. 给 `Triage` 加校验器：P1 时 `first_check` 不少于 6 个字；B 组再跑 100 次。
3. schema 最前面加一个 `reason` 字段，比较类别正确率。
