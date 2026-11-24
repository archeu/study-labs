# 工具调用的一来一回：模型提议，你的代码执行

对应页面：[工具调用的一来一回：模型提议，你的代码执行](https://study.ainiya.de/agent/tool-use-roundtrip.html)

只给模型一个工具 `parse_gc_log`（来自 `../pano-04-triage-agent/oncall.py`），走一个来回，把每条消息原样打出来。

## 怎么跑

先起本机模型（见 [pano-04-triage-agent](../pano-04-triage-agent/README.md)），然后：

```bash
cd agent && uv sync
uv run python tool-use-roundtrip/main.py            # 一个来回：tool_use → 执行 → tool_result → 回答
uv run python tool-use-roundtrip/main.py --raw      # 渲染后的提示词 + 模型原始输出（只对 llama-server 有效）
uv run python tool-use-roundtrip/main.py --tokens   # 工具说明占多少 token
```

## 预期输出

2026-11-24，2 核 ARM，llama.cpp b12618，Qwen3-1.7B Q4_K_M，anthropic 2.12.0，Python 3.13：

```
[33.0s] stop_reason = tool_use
 {"type": "tool_use", "id": "RBbkhukvKrc6yWKsFUvH6MhISHBdaQXf", "name": "parse_gc_log", "input": {"since": "20:00"}}
 {"type": "tool_result", "tool_use_id": "RBbkhukvKrc6yWKsFUvH6MhISHBdaQXf", "content": "7 次停顿，最长 1966ms；……"}
[31.5s] stop_reason = end_turn
请求 2 一共发了 3 条消息：user → assistant(tool_use) → user(tool_result)

--raw：提示词里出现 "# Tools ... <tools>{...}</tools> ... <tool_call></tool_call>"，
       模型原始输出 "<think>…</think>\n<tool_call>\n{"name": "parse_gc_log", "arguments": {"since": "20:00"}}\n</tool_call>"（19.7s）
--tokens：不带工具 207 token；带 1 个工具 388（+181）；带 5 个工具 792（+585）
```

## 练习

先猜结果，再运行。

1. 把 `tool_use_id` 改成 `"toolu_x"`，连 Claude 看报错原文；连本机看它查不查。
2. 给执行包一层 try，`TypeError` 时回 `is_error`；把 schema 里的 `since` 改名为 `start`，看模型第二次会不会改对。
3. 把 `TOOLS` 换回全部 5 个，问同一个问题，看它会不会一轮要两个。
