"""同一个 oncall 服务端，同样调 100 次 search_logs：stdio 和 Streamable HTTP 各花多少时间。

先在另一个终端起 HTTP 服务端：uv run python mcp-transports/oncall_http.py
再跑：uv run python mcp-transports/compare.py

还顺带看一件事：两个 HTTP 客户端同时连，服务端还是一个进程；stdio 是一个客户端一个进程。
"""

import asyncio
import statistics
import sys
import time
from pathlib import Path

from mcp import Client, StdioServerParameters

HERE = Path(__file__).resolve().parent
STDIO = StdioServerParameters(command=sys.executable, args=[str(HERE.parent / "pano-07-mcp-call" / "oncall_server.py")])
HTTP = "http://127.0.0.1:8765/mcp"
N = 100
ARGS = {"keyword": "ERROR", "since": "20:06"}


async def bench(name: str, server) -> None:
    t0 = time.perf_counter()
    async with Client(server) as c:
        connect = (time.perf_counter() - t0) * 1000
        await c.call_tool("search_logs", ARGS)  # 热身一次
        laps = []
        for _ in range(N):
            t = time.perf_counter()
            r = await c.call_tool("search_logs", ARGS)
            laps.append((time.perf_counter() - t) * 1000)
        assert not r.is_error
    laps.sort()
    print(f"{name:6} 连上 {connect:7.1f} ms   每次 tools/call  p50 {statistics.median(laps):5.2f} ms  "
          f"p95 {laps[int(N * 0.95) - 1]:5.2f} ms  最慢 {laps[-1]:5.2f} ms")


async def two_clients() -> None:
    async def one(tag: str) -> str:
        async with Client(HTTP) as c:
            r = await c.call_tool("search_logs", {"keyword": "Export"})
            return f"{tag} 拿到 {len(r.content[0].text.splitlines())} 行"
    print("两个 HTTP 客户端同时连同一个服务端进程：", " / ".join(await asyncio.gather(one("A"), one("B"))))


async def main() -> None:
    await bench("stdio", STDIO)
    await bench("HTTP", HTTP)
    await two_clients()


if __name__ == "__main__":
    asyncio.run(main())
