"""一个客户端，连两个服务端：Python 写的 oncall（stdio）和 Java 写的 tickets（HTTP，先起 Java 那边）。

    uv run python mcp-server-build/call_both.py 2>/dev/null
"""
import asyncio, sys, time
from pathlib import Path
from mcp import Client, StdioServerParameters
A = Path(__file__).resolve().parent.parent
async def main():
    py = StdioServerParameters(command=sys.executable, args=[str(A / "mcp-server-build/oncall_server.py")])
    for name, server, tool, args in [("oncall（Python，stdio）", py, "search_logs", {"keyword": "Export", "since": "20:06"}),
                                     ("tickets（Java，HTTP）", "http://127.0.0.1:8766/mcp", "get_incident", {"id": "INC-1108"})]:
        t = time.perf_counter()
        async with Client(server) as c:
            tools = [x.name for x in (await c.list_tools()).tools]
            r = await c.call_tool(tool, args)
        print(f"{name:22} 工具 {tools}  {tool} → is_error={r.is_error}  {r.content[0].text[:50]!r}  共 {(time.perf_counter()-t)*1000:.0f} ms")
asyncio.run(main())
