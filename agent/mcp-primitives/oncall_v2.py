"""oncall 服务端 v2：三种能力各用全。

工具（模型决定何时调）：search_logs、parse_gc_log（结构化输出）、heap_dump（标了“有破坏性”）
资源（应用决定何时读）：oncall://runbook/order-svc（固定）、oncall://gc-log/{date}（模板）
提示词模板（用户决定何时用）：triage(service, alert_time)

数据和站 04 同一份（2026-11-08 20:16 order-svc 变慢，假数据）。
"""

import re
import sys
from pathlib import Path

from pydantic import BaseModel, Field

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pano-04-triage-agent"))
import oncall  # noqa: E402

from mcp.server import MCPServer  # noqa: E402
from mcp.server.mcpserver import UserMessage  # noqa: E402
from mcp.server.mcpserver.exceptions import ResourceNotFoundError, ToolError  # noqa: E402
from mcp_types import ToolAnnotations  # noqa: E402

mcp = MCPServer("oncall", version="0.8.0", instructions="order-svc 值班排障用。时间参数用 HH:MM，告警前 10 分钟开始查。")
HHMM = re.compile(r"^\d{2}:\d{2}$")


def _check_since(since: str | None) -> None:
    if since is not None and not HHMM.match(since):
        raise ToolError(f"since 要写成 HH:MM（例如 20:06），收到的是 {since!r}")  # 预料之中的失败：模型能看到这句话


# ---------- 工具：模型控制 ----------
@mcp.tool(annotations=ToolAnnotations(read_only_hint=True, open_world_hint=False))
def search_logs(keyword: str, since: str | None = None) -> str:
    """在 order-svc 应用日志里按正则查某段时间的日志行。查报错、慢请求、上线记录时用。返回最多 20 行。

    Args:
        keyword: 正则，例如 WARN|ERROR、耗时、Export
        since: 开始时间 HH:MM，例如 20:06
    """
    _check_since(since)
    re.compile(keyword)  # 坏正则在这里抛 re.error：没预料到的异常，模型只会看到一句笼统的报错
    return oncall.search_logs(keyword, since)


class GcSummary(BaseModel):
    pauses: int = Field(description="停顿次数")
    max_pause_ms: float = Field(description="最长一次停顿，毫秒")
    full_gc: int = Field(description="Full GC 次数")
    heap_after_full_gc_pct: float = Field(description="最后一次 Full GC 后堆占用百分比")


@mcp.tool(annotations=ToolAnnotations(read_only_hint=True, open_world_hint=False))
def parse_gc_log(since: str | None = None) -> GcSummary:
    """解析 order-svc 的 GC 日志，返回停顿次数、最长停顿、Full GC 次数、Full GC 后堆占用。怀疑 GC 导致变慢时用。

    Args:
        since: 开始时间 HH:MM
    """
    _check_since(since)
    rows = [r for r in oncall.GC_LOG.splitlines() if since is None or r[12:17] >= since]
    pauses = [float(re.search(r"([\d.]+)ms$", r).group(1)) for r in rows]
    fulls = [r for r in rows if "Pause Full" in r]
    after = re.search(r"->(\d+)M\((\d+)M\)", fulls[-1]) if fulls else None
    return GcSummary(pauses=len(rows), max_pause_ms=max(pauses, default=0), full_gc=len(fulls),
                     heap_after_full_gc_pct=round(int(after.group(1)) / int(after.group(2)) * 100, 1) if after else 0)


@mcp.tool(annotations=ToolAnnotations(read_only_hint=False, destructive_hint=True, idempotent_hint=False))
def heap_dump(reason: str) -> str:
    """对 order-svc 做堆转储（jmap -dump:live，会触发一次 Full GC，应用停顿数秒）。会影响线上，调用前要值班人员确认。

    Args:
        reason: 为什么必须现在 dump
    """
    return f"（演示）已对 order-svc 做 heap dump，原因：{reason}。文件 /data/dump/order-svc-2026-11-08.hprof，1.1 GB"


# ---------- 资源：应用控制 ----------
@mcp.resource("oncall://runbook/order-svc", name="order-svc 值班手册", mime_type="text/markdown")
def runbook() -> str:
    """order-svc 接口变慢时的排查顺序。"""
    return ("# order-svc 接口变慢\n1. 先看告警前 10 分钟的 WARN/ERROR 和上线记录\n"
            "2. 再看 GC：Full GC 次数、停顿、回收后堆占用\n3. 线程栈看卡在哪\n4. 要 heap dump 先找值班负责人")


@mcp.resource("oncall://gc-log/{date}", name="某一天的 GC 日志", mime_type="text/plain")
def gc_log(date: str) -> str:
    """按日期取 order-svc 的原始 GC 日志，日期格式 YYYY-MM-DD。"""
    if date != "2026-11-08":
        raise ResourceNotFoundError(f"没有 {date} 的 GC 日志")
    return oncall.GC_LOG


# ---------- 提示词模板：用户控制 ----------
@mcp.prompt(title="按值班手册排障")
def triage(service: str, alert_time: str) -> list[UserMessage]:
    """生成一段排障开场白：按值班手册的顺序查，结论要带证据。"""
    return [UserMessage(f"{service} 在 {alert_time} 告警变慢。按值班手册排查：先查告警前 10 分钟的 WARN/ERROR 和上线记录，"
                        f"再看 GC，再看线程栈。每个结论后面写上证据（日志行或 GC 数字）。需要 heap dump 先问我。")]


if __name__ == "__main__":
    mcp.run("stdio")
