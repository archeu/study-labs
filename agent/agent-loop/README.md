# Agent 循环：调到不再要工具或到上限为止

对应页面：[Agent 循环：调到不再要工具或到上限为止](https://study.ainiya.de/agent/agent-loop.html)

全景页的代码削到只剩循环：每轮打印发出几条消息、完整提示多长（`count_tokens`）、花了多久、要了什么工具。

## 怎么跑

先起本机模型（见 [pano-04-triage-agent](../pano-04-triage-agent/README.md)），或者设 `ANTHROPIC_BASE_URL=` 和 key 连 Claude。

```bash
cd agent && uv sync
uv run python agent-loop/main.py          # 真模型
uv run python agent-loop/main.py --fake   # 照剧本出牌的假模型，几毫秒跑完
```

## 预期输出

2026-11-24，2 核 ARM，llama.cpp b12618，Qwen3-1.7B Q4_K_M，anthropic 2.12.0，Python 3.13（每次轮数都可能不同；全景页那次是 5 轮）：

```
第 1 轮：发出  1 条消息 /   691 token， 72.9s，stop_reason=tool_use，要了 ['search_logs', 'parse_gc_log', 'search_notes']
第 2 轮：发出  3 条消息 /  1262 token， 45.3s，stop_reason=tool_use，要了 ['thread_dump']
第 3 轮：发出  5 条消息 /  1676 token， 51.0s，stop_reason=tool_use，要了 ['search_notes']
……（第 4～9 轮都是 search_notes，结果一模一样）
第 10 轮：发出 19 条消息 /  4392 token， 85.2s，stop_reason=end_turn，要了 []

共 10 轮，累计发出输入 26225 token（每轮整段重发的总和）

最终回答：
（空：模型只写了思考就 end_turn）
```

## 练习

先猜结果，再运行。

1. 记下做过的 (name, input)，重复时不执行、回 `is_error`；用 `--fake` 写一个连调三次同一工具的剧本验证。
2. `end_turn` 但文字为空时，追问一句“请给出结论”。
3. 每轮把 messages 写进 `transcript.json`，启动时读回来接着跑；跑到第 2 轮按 Ctrl-C 试试。
