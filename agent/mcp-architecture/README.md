# MCP 的三方：主机、客户端、服务端

对应页面：[MCP 的三方：主机、客户端、服务端](https://study.ainiya.de/agent/mcp-architecture.html)

| 文件 | 是什么 |
|---|---|
| `mini_host.py` | 只用标准库的迷你主机：一个服务端配一个客户端（子进程 + 管道），先 `server/discover`、失败回退 `initialize`，工具加 `mcp__服务端__` 前缀合并，各调一次，最后打印每个服务端收到了哪些消息 |
| `gateway_server.py` | 40 行手写的老协议（2025-11-25）服务端，不认识 `server/discover`；也有一个叫 `search_logs` 的工具 |

oncall 服务端用的是 `../pano-07-mcp-call/oncall_server.py`（官方 SDK `mcp` 2.x）。

## 怎么跑

```bash
cd agent && uv sync
uv run python mcp-architecture/mini_host.py           # oncall 走新协议，gateway 回退握手
uv run python mcp-architecture/mini_host.py --legacy  # 两个都走握手
```

## 预期输出

2026-11-26，2 vCPU ARM，Python 3.13.18，mcp 2.3.0：

```
    5.2 ms  [oncall] → server/discover  id=1  265 B
  675.9 ms  [oncall] ← result  id=1  485 B
  675.9 ms  [oncall] 新协议 2026-07-28，服务端自报 {'name': 'oncall', 'version': '0.7.0'}，能力 ['prompts', 'resources', 'tools']
  676.0 ms  [gateway] → server/discover  id=1  265 B
  676.2 ms  [gateway] ← error -32601  id=1  102 B
  676.3 ms  [gateway] → initialize  id=2  171 B
  676.3 ms  [gateway] ← result  id=2  158 B
  676.4 ms  [gateway] → notifications/initialized  id=None  57 B
  ...
合并后的工具表（5 个）： mcp__oncall__search_logs, ..., mcp__gateway__search_logs
  851.3 ms  全部关闭。主机知道问题：“order-svc 20:16 告警变慢，查一下根因”
   oncall 一共收到 3 条消息：['server/discover', 'tools/list', 'tools/call']
   gateway 一共收到 5 条消息：['server/discover', 'initialize', 'notifications/initialized', 'tools/list', 'tools/call']
```

`--legacy` 时 `tools/list` 请求从 260 B 变成 65 B（不再带 `_meta`）。

对照实验（页面 ⑥ 坑 1）：官方 SDK 的 `Client(server, mode="2026-07-28")` 连 `gateway_server.py`，报 `ValidationError: 3 validation errors for ListToolsResult`（缺 `cacheScope` 等），不是“版本不对”。

## 练习

先猜结果，再运行。

1. 在 `SERVERS` 里把 gateway 再配一份叫 `gateway2`，合并表里会有几个 `search_logs`？去掉前缀后哪个被覆盖了？
2. 让 `gateway_server.py` 收到 `server/discover` 时什么都不回。迷你主机等多久回退？
3. 把 `request()` 改成“id → Future”表，同时给 oncall 发两个 `tools/call`（让第一个慢 1 秒），确认结果没有串。
