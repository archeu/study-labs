# 上下文快满了：压缩、摘要、子任务隔离

对应页面：[上下文快满了：压缩、摘要、子任务隔离](https://study.ainiya.de/agent/context-compaction.html)

## 怎么跑

先起本地服务（没有 API key 也能跑；写摘要要比 0.6B 大一点的模型）：

```bash
llama-server -m Qwen3-1.7B-Q4_K_M.gguf --jinja -np 1 -c 16384 --port 8090   # llama.cpp b12618，unsloth/Qwen3-1.7B-GGUF
cd agent && uv sync
ANTHROPIC_BASE_URL=http://127.0.0.1:8090 uv run python context-compaction/main.py   # 本机 CPU 约半小时
```

- `scene.py`：2026-11-09 10:42 的假现场（GC 日志、应用日志、线程栈、堆直方图），每个工具结果第一行是工具自己写的“要点”。复用 `context-engineering/context_builder.py`。
- `compact.py`：`clear_old_tool_results`（清旧结果，留要点）、`summarize` + `compacted_messages`（交接摘要）、`run_subagent`（子 agent）。
- `main.py`：三招各量 token、各追问一次，再把直方图交给子 agent。

需要的第三方包用 `uv add <包>` 加到 `agent/pyproject.toml`。

## 预期输出

2026-11-24，2 核 ARM，Python 3.13，llama-server b12618 + Qwen3-1.7B Q4_K_M（关掉思考）：

```
原样 11905 token；清旧结果 2802；摘要 1606（写摘要用了 1045s）
摘要里对的数字来自工具的“要点”行；模型自己编了“41 次”“平均等待 199ms”，还把 10:42 当成晚高峰
子 agent 读了 7627 token，交回 OrderSnapshot 2252210640 字节；主上下文：直接塞直方图 +7535，只收结论 +156
```

1.7B 对着原样历史也把 Full GC 14 次答成 2 次，所以追问分不出三招高下；用 Claude 再跑才有意义。

## 练习

先猜结果，再运行。

1. 用 Claude 再跑 `main.py`，看三种办法的两问各答对几个；把“最长那次几点”写进 `parse_gc_log` 的要点行再跑。
2. 给 `run_subagent` 的任务加上约束（只读、晚高峰不 dump、导出已排除），要求返回 JSON。
3. 每轮前用 `count_tokens` 量一次：超阈值先清，清完仍超 80% 再摘要，打印前后 token 数。
