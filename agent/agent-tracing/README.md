# Tracing：每次模型和工具调用的耗时、token

对应页面：[Tracing：每次模型和工具调用的耗时、token](https://study.ainiya.de/agent/agent-tracing.html)

给值班助手接上 OpenTelemetry（`opentelemetry-sdk` 1.45.2）。span 的名字和属性照 OpenTelemetry GenAI 语义约定（`open-telemetry/semantic-conventions-genai`，v0.1.0，Development）：

```
invoke_agent oncall          gen_ai.prompt.name / gen_ai.prompt.version，eval.case.id，eval.passed
  chat qwen3-1.7b            gen_ai.usage.input_tokens（含缓存命中）/ cache_read.input_tokens / output_tokens
  execute_tool search_logs   gen_ai.tool.name / gen_ai.tool.call.id / arguments / result（后两个 opt-in）
```

| 命令 | 做什么 |
|---|---|
| `live 02 --prompt @3` | 真跑一条，边跑边记 span，写进 `traces/live.jsonl`，终端里画瀑布 |
| `convert <run>` | 把 eval-dataset 运行时存下的调用记录转成 OTel span（不重跑模型），写进 `traces/<run>.jsonl` |
| `show <jsonl> [--case 01]` | 终端瀑布图 |
| `where <run>` | 50 条的时间和 token 花在哪 |

导出器是自己写的 `JsonlExporter`（每个 span 一行 JSON）；线上换成 OTLP 导出器即可。`traces/` 里只提交了 `live.jsonl`，其余用 `convert` 生成。

## 怎么跑

```bash
cd agent && uv sync
uv run python agent-tracing/main.py live 02 --prompt @3
uv run python agent-tracing/main.py convert base-@3-r1
uv run python agent-tracing/main.py show agent-tracing/traces/base-@3-r1.jsonl --case 01
uv run python agent-tracing/main.py where base-@3-r1
```

## 预期输出

2026-11-26，2 核 ARM，llama.cpp b12618，Qwen3-1.7B Q4_K_M（关思考，温度 0.8），Python 3.13：

```
where base-@3-r1：50 条，模型调用 247 次；模型 3178s，工具 0.01s；每条 P50 70s、P95 93s
  每次模型调用平均 12.9s，输入平均 1169 token（缓存命中 1077），输出 59
  第 1 轮平均 6.0s、31 token；最后一轮（写结论）35.1s、188 token，占全部模型时间 55%
@4 这一跑的第一条（01）：第 1 轮缓存命中 0，946 token 重算，31 秒（@3 同位置 6 秒）
```

## 练习

1. 工具参数和返回值先不记，等 `eval.passed=false` 再补进根 span 的事件里。
2. 在 `where` 里按缓存命中、未命中、输出三档单价算每条成本。
3. 用 `show` 找一条 @4 里看到了关键日志却仍怪 GC 的用例，指出关键证据在第几轮。
