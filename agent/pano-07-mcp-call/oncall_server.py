"""值班助手的 MCP 服务端（Python，stdio）：把站 04 的 4 个只读工具搬出来，谁的 agent 都能接。

三种能力各放一个：
- 工具（tools）：search_logs / parse_gc_log / thread_dump / search_notes，模型决定什么时候调
- 资源（resources）：oncall://runbook/order-svc，宿主（或用户）决定什么时候读
- 提示词模板（prompts）：triage，用户在客户端里点它（Claude Code 里是 /mcp__oncall__triage）

数据是 2026-11-08 20:16 那次 order-svc 变慢的现场，和站 04 同一份（假数据）。

跑法（一般不直接跑，由客户端拉起）：
    uv run python pano-07-mcp-call/oncall_server.py
stdout 只能走协议消息；要打日志写 stderr。
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pano-04-triage-agent"))
import oncall  # noqa: E402  站 04 的现场数据和工具实现

from mcp.server import MCPServer  # noqa: E402

mcp = MCPServer("oncall", version="0.7.0", instructions="order-svc 值班排障用的只读工具。时间参数用 HH:MM，告警前 10 分钟开始查。")


@mcp.tool()
def search_logs(keyword: str, since: str | None = None) -> str:
    """在应用日志里按关键字查 order-svc 某段时间的日志行。查报错、慢请求、上线记录时用。返回最多 20 行。

    Args:
        keyword: 正则，例如 WARN|ERROR、耗时、Export
        since: 开始时间 HH:MM，例如 20:00
    """
    return oncall.search_logs(keyword, since)


@mcp.tool()
def parse_gc_log(since: str | None = None) -> str:
    """解析 order-svc 的 GC 日志，返回停顿次数、最长停顿、Full GC 次数、Full GC 后老年代剩多少。怀疑 GC 导致变慢时用。

    Args:
        since: 开始时间 HH:MM
    """
    return oncall.parse_gc_log(since)


@mcp.tool()
def thread_dump() -> str:
    """对 order-svc 做一次线程栈快照（jstack，停顿毫秒级），返回按状态归类的摘要。看线程卡在哪时用。"""
    return oncall.thread_dump()


@mcp.tool()
def search_notes(query: str) -> str:
    """在值班知识库（学习笔记）里搜相关经验，返回最多 2 条摘要和页面名。

    Args:
        query: 现象关键词，例如 Full GC 老年代
    """
    return oncall.search_notes(query)


@mcp.resource("oncall://runbook/order-svc", name="order-svc runbook", mime_type="text/markdown")
def runbook() -> str:
    """order-svc 的值班手册：接口变慢时先看什么。"""
    return ("# order-svc 接口变慢\n1. 先看告警前 10 分钟的 WARN/ERROR 和上线记录\n"
            "2. 再看 GC：Full GC 次数、停顿、回收后老年代占比\n3. 线程栈看卡在哪\n4. 要 heap dump 先找值班负责人")


@mcp.prompt()
def triage(service: str = "order-svc", alert_time: str = "20:16") -> str:
    """生成一段排障开场白：按 runbook 的顺序查。"""
    return f"{service} 在 {alert_time} 告警变慢。按 runbook：先查告警前 10 分钟的日志和上线记录，再看 GC，再看线程栈，最后给出根因和证据。"


if __name__ == "__main__":
    print("oncall MCP server 启动（stdio）", file=sys.stderr)
    mcp.run("stdio")
