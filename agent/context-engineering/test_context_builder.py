"""不需要模型服务的检查：拼法的结构性质。  uv run pytest context-engineering/"""

import json

from context_builder import MEMORIES, build_request, truncate_tool_result


def prefix(req) -> str:
    """近似“渲染后的前缀”：工具、系统提示按固定顺序序列化（Claude 的渲染顺序是 tools → system → messages）。"""
    return json.dumps(req["tools"], ensure_ascii=False) + json.dumps(req["system"], ensure_ascii=False)


def test_good_layout_keeps_prefix_stable():
    a = build_request("问题一", MEMORIES, now="10:42", layout="good")
    b = build_request("问题二", MEMORIES[:1], now="10:57", layout="good")
    assert prefix(a) == prefix(b)          # 记忆和问题变了，前缀一个字节都没变


def test_bad_layout_breaks_prefix():
    a = build_request("问题一", MEMORIES, now="10:42", layout="bad")
    b = build_request("问题一", MEMORIES, now="10:43", layout="bad")
    assert prefix(a) != prefix(b)          # 只差一分钟，整个前缀就不同了


def test_memories_carry_date_and_source():
    req = build_request("q", MEMORIES, now="10:42", layout="good")
    mem_text = req["messages"][0]["content"][0]["text"]
    assert mem_text.count('date="') == 3 and mem_text.count('source="') == 3


def test_truncate_keeps_head_tail_and_says_how_much():
    text = "\n".join(f"line {i}" for i in range(1000))
    out = truncate_tool_result(text, max_lines=40, keep_tail=10)
    lines = out.splitlines()
    assert lines[0] == "line 0" and lines[-1] == "line 999"
    assert "省略 960 行" in out and len(lines) == 41
