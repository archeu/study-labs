# 全景：Claude Code 调用你的 MCP 工具

对应页面：[全景：Claude Code 调用你的 MCP 工具](https://study.ainiya.de/agent/pano-07-mcp-call.html)

值班助手 v2 的第一步：把站 04 的 4 个只读工具搬进一个 MCP 服务端，谁的 agent 都能接。

| 文件 | 是什么 |
|---|---|
| `oncall_server.py` | MCP 服务端（官方 Python SDK `mcp` 2.x 的 `MCPServer`，stdio）：4 个工具 + 1 个资源 `oncall://runbook/order-svc` + 1 个提示词模板 `triage`。数据 import 自 `pano-04-triage-agent/oncall.py` |
| `wiretap.py` | stdio 窃听器：夹在客户端和服务端中间，两个方向的每一行原样转发并记进 JSONL。只用标准库 |
| `main.py` | 用 SDK 的 `Client` 当客户端：拉起服务端 → 协商版本 → 列三种能力 → 调 `search_logs` → 关掉，每一步计时并打印报文 |

## 怎么跑

```bash
cd agent && uv sync
uv run python pano-07-mcp-call/main.py            # 新协议 2026-07-28：先 server/discover
uv run python pano-07-mcp-call/main.py --legacy   # 老协议：initialize 握手
```

报文写到 `pano-07-mcp-call/wire.jsonl`（不进仓库）。

### 抓 Claude Code 自己发的报文（不调模型，不花额度）

`claude mcp get` 会真的连一次服务端做健康检查。把 command 换成窃听器即可（`-s local` 只记在当前目录，用完删掉）：

```bash
A=$PWD; PY=$A/.venv/bin/python
claude mcp add -s local oncall -- $PY $A/pano-07-mcp-call/wiretap.py /tmp/wire-cc.jsonl $PY $A/pano-07-mcp-call/oncall_server.py
claude mcp get oncall
claude mcp remove -s local oncall
```

### 真让 Claude Code 调一次（会用你的账号额度）

```bash
claude mcp add -s local oncall -- uv run --project $PWD python $PWD/pano-07-mcp-call/oncall_server.py
claude -p "order-svc 20:16 告警变慢，查一下 20:06 以后的 WARN 和 ERROR" --allowedTools "mcp__oncall__search_logs"
```

## 预期输出

2026-11-26，2 vCPU ARM，Python 3.13.18，mcp 2.3.0：

```
   711.9 ms  (+  711.9)  连上（拉起进程 + 协商版本）
   714.6 ms  (+    2.7)  tools/list → 4 个工具
   717.0 ms  (+    2.4)  resources/list → 1 个资源
   718.6 ms  (+    1.7)  prompts/list → 1 个模板
   765.7 ms  (+   47.1)  tools/call search_logs
   949.4 ms  (+  183.7)  关掉（关 stdin，等进程退出）

报文（wire.jsonl）：
     0.6 ms  c2s   id=1   server/discover  261 B
   676.3 ms  s2c   id=1   result  485 B          ← 0.68 s 全是 Python 起进程 + 加载 SDK
   677.5 ms  c2s   id=2   tools/list  256 B
   679.0 ms  s2c   id=2   result  2416 B
   ...
   684.6 ms  c2s   id=5   tools/call  337 B
   686.7 ms  s2c   id=5   result  1826 B         ← 报文往返 2 ms（客户端那边多出来的 45 ms 是 SDK 自己的处理）
   901.5 ms  exit  code 0
```

`--legacy` 时前三条换成 `initialize` → result → `notifications/initialized`，之后的请求不带 `_meta`（`tools/list` 51 B 对 256 B）。

Claude Code 2.1.332 健康检查抓到的顺序（同一台机器）：

```
server/discover (id "server-discover-probe-1", _meta 里 protocolVersion 2026-07-28, clientInfo claude-code 2.1.332)
subscriptions/listen (toolsListChanged / promptsListChanged / resourcesListChanged)
server/discover (id 0)
tools/list (id 1)
```

对只会老协议的服务端（不认识 `server/discover`，回 -32601），Claude Code 接着发 `initialize`（protocolVersion 2025-11-25）、`notifications/initialized`、`tools/list`。

## 练习

先猜结果，再运行。

1. 在 `oncall_server.py` 的 `mcp.run` 之前加一行 `print("debug")`，跑 `main.py`。报文里能看到这一行吗？为什么？（提示：读 `mcp/server/stdio.py` 里的 `_claim_fd`）
2. 自己写一个 20 行的标准库服务端，只处理 `initialize` 和 `tools/list`，在回复前 `print("x", end="", flush=True)`。用 `main.py` 连它，哪条消息会丢？客户端等多久？
3. 把 `main.py` 改成同时连两个服务端（oncall 和你写的那个），把两边的工具合并成一张表，同名工具怎么处理？（对照 Claude Code 的 `mcp__服务端__工具` 命名）
