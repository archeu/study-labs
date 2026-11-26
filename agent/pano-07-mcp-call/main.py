"""全景实测：一个 MCP 客户端拉起 oncall 服务端，走完“发现 → 列能力 → 调工具 → 关掉”，每一步计时，报文全记下来。

Claude Code 做的是同一件事（它的报文用 wiretap.py 抓，见 README）。这里用官方 Python SDK 的 Client 当客户端，
不用模型、不用 key：模型那一步（决定调哪个工具、填什么参数）由脚本写死成 search_logs(WARN|ERROR, 20:06)。

    uv run python pano-07-mcp-call/main.py            # 新协议（2026-07-28）：server/discover 探测
    uv run python pano-07-mcp-call/main.py --legacy   # 老协议：initialize 握手
报文写到 pano-07-mcp-call/wire.jsonl（不进仓库）。
"""

import asyncio
import json
import sys
import time
from pathlib import Path

from mcp import Client, StdioServerParameters

HERE = Path(__file__).resolve().parent
WIRE = HERE / "wire.jsonl"


async def main(legacy: bool) -> None:
    WIRE.unlink(missing_ok=True)
    server = StdioServerParameters(
        command=sys.executable,
        args=[str(HERE / "wiretap.py"), str(WIRE), sys.executable, str(HERE / "oncall_server.py")],
    )
    marks: list[tuple[str, float]] = []
    t0 = time.perf_counter()

    def mark(step: str) -> None:
        marks.append((step, (time.perf_counter() - t0) * 1000))

    async with Client(server, mode="legacy" if legacy else "auto") as c:
        mark("连上（拉起进程 + 协商版本）")
        tools = await c.list_tools()
        mark(f"tools/list → {len(tools.tools)} 个工具")
        res = await c.list_resources()
        mark(f"resources/list → {len(res.resources)} 个资源")
        prompts = await c.list_prompts()
        mark(f"prompts/list → {len(prompts.prompts)} 个模板")
        r = await c.call_tool("search_logs", {"keyword": "WARN|ERROR", "since": "20:06"})
        mark("tools/call search_logs")
    mark("关掉（关 stdin，等进程退出）")

    prev = 0.0
    for step, t in marks:
        print(f"{t:8.1f} ms  (+{t - prev:7.1f})  {step}")
        prev = t
    text = r.content[0].text if r.content else ""
    print(f"\nisError={r.is_error}  结果 {len(text)} 字，前两行：")
    print("\n".join(text.splitlines()[:2]))

    print(f"\n报文（{WIRE.name}）：")
    for line in WIRE.read_text(encoding="utf-8").splitlines():
        rec = json.loads(line)
        m = rec["msg"]
        what = m.get("method") or ("result" if "result" in m else "error" if "error" in m else m)
        size = len(json.dumps(m, ensure_ascii=False).encode())
        print(f"{rec['t']:8.1f} ms  {rec['dir']:4}  id={m.get('id')!s:<24} {what}  {size} B")


if __name__ == "__main__":
    asyncio.run(main("--legacy" in sys.argv))
