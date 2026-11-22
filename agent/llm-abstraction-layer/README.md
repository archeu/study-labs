# 换模型只改一层：模型抽象层

对应页面：[换模型只改一层：模型抽象层](https://study.ainiya.de/agent/llm-abstraction-layer.html)

- `llm_layer.py`：约 120 行的抽象层。按任务配置（主模型、备用、参数），两个适配器（anthropic SDK / OpenAI 兼容 HTTP），按模型能力过滤参数，错误分成可重试和不可重试，失败切备用，用量统一成 input / cached / output。
- `main.py`：业务代码只有 `layer.complete("triage", ...)` 一行，依次加载三份配置。
- `config_a.json` / `config_b.json` / `config_c.json`：Messages 格式的本机 Qwen2.5；OpenAI 兼容格式的本机 Qwen3；主服务地址写错 + 备用不收 temperature。

## 怎么跑

需要 [llm-next-token](../llm-next-token/README.md) 的 8090 服务和 [llm-reasoning-thinking](../llm-reasoning-thinking/README.md) 的 8091 服务（Qwen3，`--jinja`）。

```bash
cd agent && uv sync
cd llm-abstraction-layer && uv run python main.py
```

## 预期输出

2026-11-22，anthropic 2.12.0，llama.cpp b12618：

```
== config_a.json   local-messages qwen2.5-0.5b  2853 ms  用量 {'input': 81, 'cached': 0, 'output': 20}
== config_b.json   local-openai   qwen3-0.6b    1383 ms  用量 {'input': 85, 'cached': 7, 'output': 18}
== config_c.json   local-messages qwen2.5-0.5b  2224 ms
   这个模型不支持、已过滤的参数：['temperature']
   先失败、已跳过：['main（APIConnectionError: Connection error.）']
```

两个小模型的回答质量很差（照抄类别列表、类别判错），这一页看的是“换”的成本，不是质量。

## 练习

1. 适配器里加测试开关：模型名是 `bad-model` 时抛不可重试的错误，确认不会切备用。
2. 配置里支持 `models.<名字>.system`，按模型覆盖提示词。
3. 每次 `complete` 记一条（任务、模型、是否备用、毫秒、用量），打印走备用的比例。
