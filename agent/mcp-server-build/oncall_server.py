"""Python 那一个：oncall 服务端的“正式版”写法。和全景页那份比，多了四样：

1. lifespan：启动时把日志读进内存（真实场景是打开文件句柄、连数据库），每次调用直接用；
2. Context：工具里拿 lifespan 里的东西、给客户端发进度；
3. ToolError：能预料的失败写成人话，模型据此改参数重试；
4. 日志一律走 stderr（logging 默认就是 stderr），stdout 留给协议。

    uv run python mcp-server-build/oncall_server.py            # stdio（Claude Code 用）
    uv run python mcp-server-build/oncall_server.py --http     # Streamable HTTP，127.0.0.1:8767
测试：uv run pytest mcp-server-build/ -q（不起进程，内存里直接连）
"""

import logging
import re
import sys
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pano-04-triage-agent"))
import oncall  # noqa: E402  现场数据

from mcp.server import MCPServer  # noqa: E402
from mcp.server.mcpserver import Context  # noqa: E402
from mcp.server.mcpserver.exceptions import ToolError  # noqa: E402
from mcp_types import ToolAnnotations  # noqa: E402

log = logging.getLogger("oncall")  # 默认写 stderr
HHMM = re.compile(r"^\d{2}:\d{2}$")


@dataclass
class Logs:
    app: list[str]
    gc: list[str]


@asynccontextmanager
async def lifespan(server: MCPServer) -> AsyncIterator[Logs]:
    logs = Logs(app=oncall.APP_LOG.splitlines(), gc=oncall.GC_LOG.splitlines())
    log.info("载入日志：应用 %d 行，GC %d 行", len(logs.app), len(logs.gc))
    yield logs  # 进程活着期间，每个请求都能拿到它
    log.info("关闭")


mcp = MCPServer("oncall", version="1.0.0", lifespan=lifespan,
                instructions="order-svc 值班排障用的只读工具。时间参数用 HH:MM，告警前 10 分钟开始查。")


def _since_ok(since: str | None) -> None:
    if since is not None and not HHMM.match(since):
        raise ToolError(f"since 要写成 HH:MM（例如 20:06），收到的是 {since!r}")


@mcp.tool(annotations=ToolAnnotations(read_only_hint=True, open_world_hint=False))
async def search_logs(keyword: str, ctx: Context, since: str | None = None, limit: int = 20) -> str:
    """在 order-svc 应用日志里按正则查某段时间的日志行。查报错、慢请求、上线记录时用。

    Args:
        keyword: 正则，例如 WARN|ERROR、耗时、Export
        since: 开始时间 HH:MM，例如 20:06
        limit: 最多返回几行，默认 20
    """
    _since_ok(since)
    try:
        pat = re.compile(keyword)
    except re.error as e:
        raise ToolError(f"keyword 不是合法的正则：{e}。要查字面上的括号请写成 \\(") from e
    logs: Logs = ctx.request_context.lifespan_context
    rows = [r for r in logs.app if pat.search(r) and (since is None or r[11:16] >= since)]
    log.info("search_logs %r since=%s 命中 %d 行", keyword, since, len(rows))  # 写 stderr；协议里的 logging 在 2026-07-28 已弃用
    if not rows:
        return f"没查到：{since or '全天'} 之后没有匹配 {keyword!r} 的行"
    more = f"\n……还有 {len(rows) - limit} 行没显示，缩小时间或换关键字" if len(rows) > limit else ""
    return "\n".join(rows[:limit]) + more


@mcp.tool(annotations=ToolAnnotations(read_only_hint=True, open_world_hint=False))
async def parse_gc_log(ctx: Context, since: str | None = None) -> str:
    """解析 order-svc 的 GC 日志，返回停顿次数、最长停顿、Full GC 次数和回收后堆占用。怀疑 GC 导致变慢时用。

    Args:
        since: 开始时间 HH:MM
    """
    _since_ok(since)
    logs: Logs = ctx.request_context.lifespan_context
    rows = [r for r in logs.gc if since is None or r[12:17] >= since]
    for i, _ in enumerate(rows, 1):
        await ctx.report_progress(i, len(rows), "逐行解析 GC 日志")  # 长任务时客户端能看到进度
    fulls = [r for r in rows if "Pause Full" in r]
    pauses = [float(re.search(r"([\d.]+)ms$", r).group(1)) for r in rows]
    return (f"{since or '全天'} 起：停顿 {len(rows)} 次，最长 {max(pauses, default=0):.0f} ms，Full GC {len(fulls)} 次；"
            f"最后一次 Full GC 后堆 {re.search(r'->(\d+M\(\d+M\))', fulls[-1]).group(1) if fulls else '—'}")


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")  # stderr
    if "--http" in sys.argv:
        mcp.run("streamable-http", host="127.0.0.1", port=8767)
    else:
        mcp.run("stdio")
