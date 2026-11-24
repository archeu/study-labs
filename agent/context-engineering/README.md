# 上下文工程：放什么、放哪、放多少

对应页面：[上下文工程：放什么、放哪、放多少](https://study.ainiya.de/agent/context-engineering.html)

## 怎么跑

先起一个实现了 `/v1/messages` 的本地服务（没有 API key 也能跑）：

```bash
# llama.cpp b12618 自己编译出 llama-server；模型 unsloth/Qwen3-0.6B-GGUF 的 Q4_K_M
llama-server -m Qwen3-0.6B-Q4_K_M.gguf --jinja -np 1 -c 16384 --port 8090
```

```bash
cd agent && uv sync
export ANTHROPIC_BASE_URL=http://127.0.0.1:8090
uv run python context-engineering/main.py         # 逐块数 token、预算表、截断前后
uv run python context-engineering/cache_order.py  # 记忆放哪：bad / good 两种拼法各发两次
uv run pytest context-engineering/                # 不连模型：前缀稳定性、截断
```

- `context_builder.py`：拼装器。`build_request(layout="good"|"bad")`、`render_memories`（带日期和来源）、`truncate_tool_result`（留头留尾）。工具定义复用 `pano-04-triage-agent/oncall.py`。

需要的第三方包用 `uv add <包>` 加到 `agent/pyproject.toml`。

## 预期输出

2026-11-24，2 核 ARM，Python 3.13，llama-server b12618 + Qwen3-0.6B Q4_K_M：

```
工具说明 5 份 590 / + 系统提示 393 / + 记忆 3 条 + 问题 256，累计 1248
GC 日志原样 3000 行 107998 token；截断后 40 行 1472 token
bad   第 2 次  命中    20  重算  1124  耗时   13.4s
good  第 2 次  命中   995  重算   149  耗时    2.5s
```

## 练习

先猜结果，再运行。

1. 让 `truncate_tool_result` 按 token 而不是按行截断（超过 1,500 就砍）。
2. 只把“现在是 …”挪到问题里、记忆仍留在系统提示里，先猜第 2 次命中多少，再跑 `cache_order.py`。
3. 每次请求打印各块 token 和 `cache_read_input_tokens`，连续 3 次命中为 0 时打一行 WARN。
