# LangGraph 上手：把站 04 的循环画成一张图

对应页面：[LangGraph 上手：把站 04 的循环画成一张图](https://study.ainiya.de/agent/langgraph-basics.html)

站 04 的排障 agent（手写 while 循环）用 LangGraph 重写：`model`、`tools` 两个节点加一条条件边。

| 文件 | 是什么 |
|---|---|
| `tools.py` | 用 `@tool` 包站 04 的三个只读工具（现场数据：2026-11-08 20:16 order-04 变慢）；`make_model()` 默认连本机 llama-server，有 key 时连 Claude |
| `graph_agent.py` | 自己画图：`StateGraph(MessagesState)` + `ToolNode` + `tools_condition` + `InMemorySaver`，`stream_mode="updates"` 打印每一步；`--draw` 打印 Mermaid |
| `prebuilt_agent.py` | 同一个 agent 用 `langchain.agents.create_agent` 一行建出来，打印它的节点 |

## 怎么跑

没有 Claude 的 key 也能跑：llama.cpp 的 llama-server 实现了 Claude 的 `/v1/messages`（含 tools）。

```bash
# 本机模型（llama.cpp b12618 源码编译，模型 Qwen3-1.7B Q4_K_M，1.1 GB）
llama-server -m Qwen3-1.7B-Q4_K_M.gguf --jinja -c 16384 --port 8090 -a qwen3-1.7b

cd agent && uv sync
cd langgraph-basics
uv run python graph_agent.py --draw
uv run python graph_agent.py
uv run python prebuilt_agent.py
# 连 Claude：
ANTHROPIC_API_KEY=sk-ant-... MODEL=claude-sonnet-5-5 uv run python graph_agent.py
```

## 预期输出

2026-11-26，2 核 ARM，Python 3.13.18，langgraph 1.2.24，langchain 1.5.2，langchain-anthropic 1.7.13，Qwen3-1.7B（关掉思考；模型有随机性，每次走的路可能不同）：

```
  20.4s  model → 要调 ["search_logs(keyword='P99', since='20:00')"]
  20.4s  tools → search_logs 返回 7 字
  25.8s  model → 要调 ["parse_gc_log(since='20:00')"]
  25.8s  tools → parse_gc_log 返回 92 字
  59.6s  model → 结论：
根据 GC 日志分析，order-04 接口 P99 变慢的原因可能是由于频繁的 Full GC 操作。……

共 6 条消息，检查点 7 个，用时 59.6s
```

`prebuilt_agent.py`：图里的节点 `['__start__', 'model', 'tools', '__end__']`，走的路一样，用时 42.8s。

第一次查日志用了关键字 `P99`，没查到；模型没有再查，所以结论漏了 20:00 上线的导出接口。这是 1.7B 小模型的能力问题。

## 练习

先猜结果，再运行。

1. `compile(checkpointer=..., interrupt_before=["tools"])`：停下时 `graph.get_state(cfg).next` 是什么？`graph.invoke(None, cfg)` 之后呢？
2. 在 `thread_dump` 里 `raise TimeoutError`，默认 `ToolNode` 和 `ToolNode(TOOLS, handle_tool_errors=True)` 各会怎样？
3. `recursion_limit` 改成 4，模型能调几轮工具？
