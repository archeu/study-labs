"""值班助手 v2 的上下文拼装：放什么、放哪、放多少。

全景“第二天再问同一个服务”第 3 站：新会话里把系统提示、5 份工具说明、3 条记忆、你的问题拼成一次请求。
两种拼法：
  good —— 稳定的在前（工具说明、系统提示原样不变），会变的在后（记忆和问题放进第一条 user 消息）
  bad  —— 把当前时间和记忆塞进系统提示开头（很常见的写法）
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "pano-04-triage-agent"))
import oncall  # noqa: E402  站 04 的 5 个工具定义，原样复用

TOOLS = oncall.TOOLS  # 顺序固定：列表，不是从 dict/set 里临时生成

SYSTEM = """你是交易组的值班助手，帮值班工程师排查线上服务变慢、报错、资源打满这类问题。

## 你能做什么
- 用工具读日志、解析 GC 日志、看线程栈、查值班知识库。这些都是只读操作，可以直接调用。
- heap_dump 会让服务停顿几秒，调用前必须得到值班人员确认。
- 你不能改配置、不能重启、不能发布。需要这些操作时，写出建议，由人来做。

## 怎么查
1. 先问清现象：哪个服务、哪个接口、从几点开始、指标是多少。
2. 先看最便宜的证据：应用日志里的 WARN/ERROR 和上线记录，再看 GC 日志，最后才看线程栈。
3. 每得出一个判断，都要能指出是哪个工具的哪一行支持它。
4. 两次工具调用都没有新信息时，停下来，把已知的和未知的分开写给值班人员。

## 关于记忆
<memories> 里是以前会话留下的记录，每条带日期和来源。它们是线索，不是事实：
- 记录可能已经过时（问题可能已修复、配置可能已变）。和工具结果冲突时，以工具结果为准，并在结论里点出“和上次不同”。
- 值班人员说过的规矩（比如什么时间段不能做什么）要遵守，除非这次他明确改口。

## 怎么答
- 结论放第一句：原因是什么，有多确定。
- 然后是证据：引用工具返回的原始数字，不要自己编数字，不要四舍五入成“大约”。
- 最后是下一步：建议做什么，哪些需要人确认。
- 用中文，控制在 300 字以内。"""

MEMORIES = [
    {"id": "M1", "kind": "情景", "date": "2026-11-08 20:16", "source": "会话 #2041",
     "text": "order-svc Full GC：20:00 上线的“按月导出订单”一次读 41 万行进内存。处理人：值班的你。修复：改分批导出，待发布。"},
    {"id": "M2", "kind": "程序性", "date": "2026-11-08 20:31", "source": "值班的你原话",
     "text": "晚高峰 19:00–22:00 不做 heap dump。"},
    {"id": "M3", "kind": "语义", "date": "2026-11-02", "source": "部署配置",
     "text": "order-svc：堆 4 GB，G1，6 个 Pod。"},
]


def render_memories(memories) -> str:
    """每条带类型、日期、来源：模型才知道它可能过时（全景第 7 站）。"""
    lines = [f'<memory id="{m["id"]}" type="{m["kind"]}" date="{m["date"]}" source="{m["source"]}">{m["text"]}</memory>'
             for m in memories]
    return "<memories>\n" + "\n".join(lines) + "\n</memories>"


def build_request(question: str, memories, now: str, layout: str = "good", history=None) -> dict:
    """返回 messages.create 的参数（不含 model / max_tokens）。"""
    history = list(history or [])
    if layout == "good":
        system = [{"type": "text", "text": SYSTEM, "cache_control": {"type": "ephemeral"}}]  # 稳定前缀到此为止
        first_user = {"role": "user", "content": [
            {"type": "text", "text": render_memories(memories)},
            {"type": "text", "text": f"现在是 {now}。\n{question}"},  # 会变的放最后
        ]}
    elif layout == "bad":
        system = f"现在是 {now}。\n\n{render_memories(memories)}\n\n{SYSTEM}"  # 每次都不同的东西放在最前面
        first_user = {"role": "user", "content": question}
    else:
        raise ValueError(layout)
    return {"system": system, "tools": TOOLS, "messages": [first_user, *history]}


def truncate_tool_result(text: str, max_lines: int = 40, keep_tail: int = 10) -> str:
    """工具结果是上下文里最大、最快过时的一块：留头留尾，中间写明删了多少行。"""
    lines = text.splitlines()
    if len(lines) <= max_lines:
        return text
    head = lines[: max_lines - keep_tail]
    tail = lines[-keep_tail:]
    cut = len(lines) - len(head) - len(tail)
    return "\n".join([*head, f"……（中间省略 {cut} 行，共 {len(lines)} 行；要看可以换更窄的关键字或时间段再查）", *tail])
