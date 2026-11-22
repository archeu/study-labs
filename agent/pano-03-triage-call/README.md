# 全景：一次分诊调用的来回

对应页面：[全景：一次分诊调用的来回](https://study.ainiya.de/agent/pano-03-triage-call.html)

用官方 `anthropic` Python SDK 走一遍分诊调用：组装提示词 → `count_tokens` → 流式请求 → 数 SSE 事件 → 收齐看 `stop_reason` 和 `usage` → Pydantic 校验 → 不合格带着错误原因重发。

## 没有 Claude 的 key 也能跑

llama.cpp 的 llama-server 实现了 Claude 的 `/v1/messages`（含 SSE 流式和 `count_tokens`），所以 SDK 的 `base_url` 指向本机即可。先按 [llm-next-token 的 README](../llm-next-token/README.md) 起好 8090 端口的服务。

```bash
cd agent && uv sync
uv run python pano-03-triage-call/main.py
# 有 key 时连 Claude：
ANTHROPIC_BASE_URL= ANTHROPIC_API_KEY=sk-ant-... MODEL=claude-sonnet-5-5 uv run python pano-03-triage-call/main.py
```

## 预期输出

2026-11-22，2 核 ARM，anthropic 2.12.0，llama.cpp b12618，Qwen2.5-0.5B-Instruct Q4_K_M，Python 3.13：

```
第 3 站 请求体：system 254 字 + user 456 字，count_tokens = 455
第 1 次调用：首字 9.55s，总 13.45s，stop_reason=end_turn，usage 输入 455 + 缓存读 0，输出 92
   事件：message_start 1, content_block_start 1, content_block_delta 90, content_block_stop 1, message_delta 1, message_stop 1
第 9 站 校验通过：{'type': '支付', 'level': 'P3', ...}      ← 格式合格，内容错了（照抄了示例的类别）
```

同样的前缀再跑一次：`usage 输入 1 + 缓存读 454`，首字 0.07s，总 1.44s。

## 练习

1. 系统提示里再加两个不同类别的示例，看 `type` 还会不会照抄“支付”。
2. 把 `max_tokens` 改成 20，看第 8 站的 `stop_reason` 和第 9 站的重试会怎样。
3. 在系统提示开头加上当前时间，连跑两次，比较 `cache_read_input_tokens` 和首字时间。
