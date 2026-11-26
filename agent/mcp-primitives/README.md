# 三种能力：工具、资源、提示词模板

对应页面：[三种能力：工具、资源、提示词模板](https://study.ainiya.de/agent/mcp-primitives.html)

| 文件 | 是什么 |
|---|---|
| `oncall_v2.py` | oncall 服务端 v2（官方 SDK `mcp` 2.x，stdio）：3 个工具（`parse_gc_log` 返回 pydantic 模型 → 结构化结果；`heap_dump` 标了 destructive）、固定资源 `oncall://runbook/order-svc`、资源模板 `oncall://gc-log/{date}`、提示词模板 `triage(service, alert_time)` |
| `tour.py` | 客户端：列三种能力、调工具、故意出错（ToolError / 没接住的异常 / 不存在的工具）、读资源和不存在的资源、展开模板和缺参数 |

## 怎么跑

```bash
cd agent && uv sync
uv run python mcp-primitives/tour.py 2>/dev/null   # 服务端日志走 stderr，先屏蔽
# 接进 Claude Code（用完 remove）
claude mcp add -s local oncall -- uv run --project $PWD python $PWD/mcp-primitives/oncall_v2.py
```

## 预期输出

2026-11-26，2 vCPU ARM，Python 3.13.18，mcp 2.3.0（节选）：

```
  这 3 个工具的定义（名字 + 说明 + 参数 schema）共 1356 B，每次调模型都要带上
  parse_gc_log →  structuredContent = {'pauses': 7, 'max_pause_ms': 1966.375, 'full_gc': 3, 'heap_after_full_gc_pct': 72.4}
   预料之中（ToolError）    is_error=True  模型看到：Error executing tool search_logs: since 要写成 HH:MM（例如 20:06），收到的是 '8点'
   没预料到（re.error）     is_error=True  模型看到：Error executing tool search_logs
   不存在的工具            is_error=True  模型看到：Unknown tool: restart_pod（规范说应是协议错误 -32602）
  读 gc-log/2026-11-09：协议错误 code=-32602  没有 2026-11-09 的 GC 日志
  少传 alert_time：协议错误 code=-32603  Internal server error（规范说应是 -32602）
```

## 练习

1. 接住 `re.error` 改成 `ToolError`，让模型看到具体原因。
2. 用 `@mcp.completion()` 给 `triage` 的 `service` 参数加补全，客户端用 `c.complete(...)` 试。
3. `search_logs` 超过 3 行时只回前 3 行 + 一个指向 `oncall://app-log/2026-11-08` 的 `resource_link`，并补上这个资源模板。
