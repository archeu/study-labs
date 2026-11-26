# 两种传输：stdio 和 Streamable HTTP

对应页面：[两种传输：stdio 和 Streamable HTTP](https://study.ainiya.de/agent/mcp-transports.html)

| 文件 | 是什么 |
|---|---|
| `oncall_http.py` | 全景页的 oncall 服务端原样拿来，`run()` 换成 Streamable HTTP，绑 `127.0.0.1:8765`（`--json` 一律回 JSON） |
| `raw_http.sh` | 不用 SDK，用 curl 敲每一道关：完整调用、缺头、头和 body 不一致、Origin 不对、版本不认识、通知、GET、老客户端 initialize、subscriptions/listen |
| `compare.py` | 同一个服务端，stdio 和 HTTP 各调 100 次 `search_logs` 的计时；两个 HTTP 客户端同时连 |

## 怎么跑

```bash
cd agent && uv sync
uv run python mcp-transports/oncall_http.py        # 终端 1
bash mcp-transports/raw_http.sh                     # 终端 2
uv run python mcp-transports/compare.py 2>/dev/null
# 收尾：终端 1 Ctrl+C；ss -ltn | grep 8765 应该没输出
```

## 预期输出

2026-11-26，2 vCPU ARM，Python 3.13.18，mcp 2.3.0 + uvicorn：

```
1 完整的 tools/call            200 application/json
2 少了 Mcp-Method              400 {"code":-32020,"message":"mcp-method header does not match the request body's method"}
3 Mcp-Name 和 body 不一致       400 {"code":-32020,"message":"mcp-name header does not match the request body's 'name' parameter"}
4 Origin: https://evil.example 403 Invalid Origin header
5 版本 1900-01-01               400 {"code":-32022,"message":"Unsupported protocol version","data":{"supported":["2026-07-28"],"requested":"1900-01-01"}}
6 通知                          202
7 GET                           400 Missing session ID（规范建议 405）
8 老客户端 initialize           200 text/event-stream，带 mcp-session-id
9 subscriptions/listen          200 text/event-stream，x-accel-buffering: no，流一直开着

stdio  连上   674.5 ms   每次 tools/call  p50  1.65 ms  p95  2.10 ms
HTTP   连上    81.0 ms   每次 tools/call  p50  2.77 ms  p95  3.00 ms
两个 HTTP 客户端同时连同一个服务端进程： A 拿到 4 行 / B 拿到 4 行
```

stdio 服务端每个进程的内存（同一台机器）：Python SDK 写的 oncall 67 MB，标准库手写的 gateway 16 MB。

## 练习

1. 加一个 `slow_export` 工具，用 `ctx.report_progress()` 每秒报一次进度，`curl -N` 看 SSE 一段段到。
2. 调 `slow_export` 到一半 Ctrl+C，看服务端是不是当成取消；想想写操作重发会怎样。
3. 临时绑 `0.0.0.0` 并关掉 DNS 重绑定防护，用假 Origin 调一次，看完改回来。
