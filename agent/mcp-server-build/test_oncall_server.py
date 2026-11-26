"""不起进程、不走管道：把 MCPServer 对象直接交给 Client，在内存里连上测。和 Spring 的 MockMvc 一个意思。

    uv run pytest mcp-server-build/ -q
"""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from oncall_server import mcp  # noqa: E402

from mcp import Client  # noqa: E402

pytestmark = pytest.mark.anyio


@pytest.fixture
def anyio_backend():
    return "asyncio"


async def test_tools_are_listed_with_schema():
    async with Client(mcp) as c:
        tools = {t.name: t for t in (await c.list_tools()).tools}
    assert set(tools) == {"search_logs", "parse_gc_log"}
    schema = tools["search_logs"].input_schema
    assert schema["required"] == ["keyword"]          # since、limit 有默认值，不是必填
    assert "ctx" not in schema["properties"]          # Context 参数不暴露给模型
    assert tools["search_logs"].annotations.read_only_hint is True


async def test_search_logs_finds_the_export():
    async with Client(mcp) as c:
        r = await c.call_tool("search_logs", {"keyword": "Export", "since": "20:06"})
    assert not r.is_error
    assert "412,883" in r.content[0].text


async def test_bad_since_is_a_readable_tool_error():
    async with Client(mcp) as c:
        r = await c.call_tool("search_logs", {"keyword": "ERROR", "since": "8点"})
    assert r.is_error
    assert "HH:MM" in r.content[0].text               # 模型能看懂、能改


async def test_bad_regex_is_explained():
    async with Client(mcp) as c:
        r = await c.call_tool("search_logs", {"keyword": "耗时(", "since": "20:06"})
    assert r.is_error and "不是合法的正则" in r.content[0].text


async def test_gc_summary():
    async with Client(mcp) as c:
        r = await c.call_tool("parse_gc_log", {"since": "20:12"})
    assert "Full GC 3 次" in r.content[0].text
