"""把 oncall v2 的三种能力挨个摸一遍：列出来长什么样、谁来触发、返回什么、出错时是什么样。

    uv run python mcp-primitives/tour.py
"""

import asyncio
import json
import sys
from pathlib import Path

from mcp import Client, StdioServerParameters
from mcp.shared.exceptions import MCPError

HERE = Path(__file__).resolve().parent


def size(obj) -> int:
    return len(json.dumps(obj, ensure_ascii=False).encode())


async def main() -> None:
    server = StdioServerParameters(command=sys.executable, args=[str(HERE / "oncall_v2.py")])
    async with Client(server) as c:
        print("== 工具（模型控制）：每一轮都跟着请求发给模型 ==")
        tools = (await c.list_tools()).tools
        model_view = [{"name": t.name, "description": t.description, "input_schema": t.input_schema} for t in tools]
        for t in tools:
            a = t.annotations
            print(f"  {t.name:13} readOnly={a.read_only_hint!s:5} destructive={a.destructive_hint!s:5} outputSchema={'有' if t.output_schema else '无'}")
        print(f"  这 {len(tools)} 个工具的定义（名字 + 说明 + 参数 schema）共 {size(model_view)} B，每次调模型都要带上")

        r = await c.call_tool("parse_gc_log", {"since": "20:06"})
        print("\n  parse_gc_log →  structuredContent =", r.structured_content)
        print("                  content[0].text   =", r.content[0].text[:80])

        print("\n  三种出错：")
        r = await c.call_tool("search_logs", {"keyword": "ERROR", "since": "8点"})
        print(f"   预料之中（ToolError）    is_error={r.is_error}  模型看到：{r.content[0].text}")
        r = await c.call_tool("search_logs", {"keyword": "耗时(", "since": "20:06"})
        print(f"   没预料到（re.error）     is_error={r.is_error}  模型看到：{r.content[0].text}")
        try:
            r = await c.call_tool("restart_pod", {})
            print(f"   不存在的工具            is_error={r.is_error}  模型看到：{r.content[0].text}（规范说应是协议错误 -32602）")
        except MCPError as e:
            print(f"   不存在的工具            协议错误 code={e.error.code}  {e.error.message}")

        print("\n== 资源（应用控制）：读了才进上下文 ==")
        for res in (await c.list_resources()).resources:
            print(f"  固定  {res.uri}  {res.name}  {res.mime_type}")
        for tpl in (await c.list_resource_templates()).resource_templates:
            print(f"  模板  {tpl.uri_template}  {tpl.name}")
        rb = await c.read_resource("oncall://runbook/order-svc")
        print(f"  读 runbook：{len(rb.contents[0].text)} 字，第一行 {rb.contents[0].text.splitlines()[0]!r}")
        gc = await c.read_resource("oncall://gc-log/2026-11-08")
        print(f"  读 gc-log/2026-11-08：{len(gc.contents[0].text.splitlines())} 行")
        try:
            await c.read_resource("oncall://gc-log/2026-11-09")
        except MCPError as e:
            print(f"  读 gc-log/2026-11-09：协议错误 code={e.error.code}  {e.error.message}")

        print("\n== 提示词模板（用户控制）：用户点了才展开 ==")
        for p in (await c.list_prompts()).prompts:
            print(f"  {p.name}（{p.title}） 参数：{[(a.name, a.required) for a in p.arguments]}")
        g = await c.get_prompt("triage", {"service": "order-svc", "alert_time": "20:16"})
        for m in g.messages:
            print(f"  展开后 → {m.role}: {m.content.text[:70]}…")
        try:
            await c.get_prompt("triage", {"service": "order-svc"})
        except MCPError as e:
            print(f"  少传 alert_time：协议错误 code={e.error.code}  {e.error.message[:60]}（规范说应是 -32602）")


if __name__ == "__main__":
    asyncio.run(main())
