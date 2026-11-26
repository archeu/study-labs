# 写 MCP 服务端：Python 和 Java 各一个（Python 部分）

对应页面：[写 MCP 服务端：Python 和 Java 各一个](https://study.ainiya.de/agent/mcp-server-build.html)　Java 部分在 [`java/agent/mcp-server-build`](../../java/agent/mcp-server-build/)

| 文件 | 是什么 |
|---|---|
| `oncall_server.py` | oncall 正式版：lifespan 启动时载入日志、`Context` 拿 lifespan 和发进度、`ToolError` 写人话、`limit` 截断；`--http` 走 Streamable HTTP（127.0.0.1:8767） |
| `test_oncall_server.py` | 5 个测试，`Client(mcp)` 在内存里连服务端对象，不起进程 |
| `call_both.py` | 一个客户端连两个服务端：Python 的 oncall（stdio）和 Java 的 tickets（HTTP，先起 Java） |

## 怎么跑

```bash
cd agent && uv sync
uv run pytest mcp-server-build/ -q
uv run python mcp-server-build/call_both.py 2>/dev/null
# 接进 Claude Code（只连一下，不调模型）
claude mcp add -s local oncall -- $PWD/.venv/bin/python $PWD/mcp-server-build/oncall_server.py
claude mcp get oncall          # Status: ✔ Connected
claude mcp remove -s local oncall
```

真让 Claude Code 调一次（会用你的账号额度）：`claude -p "查一下 order-svc 20:06 以后的 ERROR" --allowedTools "mcp__oncall__search_logs"`。

## 预期输出

2026-11-26，2 vCPU ARM，Python 3.13.18，mcp 2.3.0：

```
5 passed in 0.68s
oncall（Python，stdio）   工具 ['search_logs', 'parse_gc_log']  search_logs → is_error=False  '2026-11-08 20:09:41.630 WARN …'  共 908 ms
tickets（Java，HTTP）     工具 ['add_comment', 'get_incident']  get_incident → is_error=False  '{"id":"INC-1108",…'  共 52 ms
```

## 练习

1. 测 `search_logs(keyword="WARN|ERROR", limit=2)`：2 行加一句“还有 N 行没显示”。
2. 把 `--http` 模式起起来，用 `Client("http://127.0.0.1:8767/mcp")` 连，确认测试里的行为一样。
