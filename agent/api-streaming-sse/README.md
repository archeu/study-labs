# 流式输出：SSE 一边生成一边推

对应页面：[流式输出：SSE 一边生成一边推](https://study.ainiya.de/agent/api-streaming-sse.html)

- `main.py compare`：同一个问题，非流式和流式的首字时间、总时间（本机 llama-server，温度 0）
- `main.py events`：清点一次流里每种事件有几个（排除 SDK 自己合成的 `text` 事件）
- `main.py midstream`：连 `fake_server.py`，推了 3 段后来一个 `overloaded_error`
- `fake_server.py`：80 行标准库写的“假 Claude”，只实现 `/v1/messages` 的流式返回，`model` 字段决定行为

## 怎么跑

先按 [llm-next-token 的 README](../llm-next-token/README.md) 起好 8090 端口的服务。

```bash
cd agent && uv sync
python3 api-streaming-sse/fake_server.py &           # 127.0.0.1:8096
uv run python api-streaming-sse/main.py compare
uv run python api-streaming-sse/main.py events
uv run python api-streaming-sse/main.py midstream
```

## 预期输出

2026-11-22，anthropic 2.12.0 → llama.cpp b12618，Qwen2.5-0.5B-Instruct Q4_K_M：

```
非流式： 2.84 s 后一次拿到全部 74 个 token
流式：   0.06 s 看到第一个字， 2.86 s 收完 74 个 token

事件顺序：message_start → content_block_start → content_block_delta → content_block_stop → message_delta → message_stop
个数：content_block_delta 54，其余各 1；message_delta 带 stop_reason=max_tokens、output_tokens=60

收到： '是，' / 'GC ' / '导致的。'
SDK 抛出 APIStatusError：status=200，{'type': 'error', 'error': {'type': 'overloaded_error', ...}}   ← 设了 max_retries=2 也不重试
```

## 练习

1. 用标准库 `http.server` 写一个 `/ask`，内部调 `messages.stream()`，每段文字转成一个 SSE 事件 flush 出去。
2. `midstream` 捕获错误后自动重试一次，重试前“清空已显示内容”。
3. 流式部分收到 20 段就 `break` 跳出 `with`，确认没有继续读。
