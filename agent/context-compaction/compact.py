"""腾地方的三种办法。都不改系统提示和工具说明（稳定前缀不动），只动 messages。

1. clear_old_tool_results  清旧工具结果：最便宜，只留工具自己写的“要点”那一行
2. summarize               摘要（compaction）：让模型写一份交接摘要，用它替换整段历史
3. run_subagent            子任务隔离：大材料放进另一个上下文里读，主上下文只收结论
"""

import copy

SUMMARY_PROMPT = """上面的排查还没结束，但上下文快满了。请写一份交接摘要，下一个接手的人只看得到这份摘要。
按四段写，每段几行：
1. 已确认的事实：每条都带工具返回的原始数字（次数、毫秒、百分比、版本号、时间），不要改写成“很多”“较长”。
2. 已排除的方向和理由。
3. 还没查的。
4. 下一步打算调用哪个工具、为什么。
不要写客套话。"""


def clear_old_tool_results(messages, keep: int = 1):
    """把除最近 keep 个之外的 tool_result 正文换成一行：工具自己写的“要点”。tool_use 本身保留。"""
    out = copy.deepcopy(messages)
    results = [blk for m in out if m["role"] == "user" and isinstance(m["content"], list)
               for blk in m["content"] if blk.get("type") == "tool_result"]
    for blk in results[:-keep] if keep else results:
        first = blk["content"].splitlines()[0]
        blk["content"] = f"[已清理，原文 {blk['content'].count(chr(10)) + 1} 行] {first}"
    return out


def summarize(client, model, req, **kw) -> str:
    """把整段历史 + 摘要要求发给模型，返回交接摘要。"""
    msgs = copy.deepcopy(req["messages"])
    msgs[-1]["content"].append({"type": "text", "text": SUMMARY_PROMPT})
    r = client.messages.create(model=model, max_tokens=900, system=req["system"], tools=req["tools"],
                               messages=msgs, **kw)
    return "".join(b.text for b in r.content if b.type == "text").strip()


def compacted_messages(req, summary: str):
    """摘要之后的 messages：第一条（记忆 + 问题）原样保留，后面整段历史换成一条交接摘要。"""
    first = copy.deepcopy(req["messages"][0])
    first["content"].append({"type": "text", "text": "<handoff>\n前面的排查过程已压缩成下面的交接摘要：\n" + summary + "\n</handoff>"})
    return [first]


SUB_SYSTEM = "你是值班助手派出的子任务助手。只读，不做任何变更。只回答交给你的那一个问题，200 字以内，带上原始数字。"


def run_subagent(client, model, task: str, material: str, **kw):
    """子 agent：一个全新的上下文，只有一句任务和材料。返回 (结论, 子 agent 自己读了多少 token)。"""
    msgs = [{"role": "user", "content": f"{task}\n\n<material>\n{material}\n</material>"}]
    r = client.messages.create(model=model, max_tokens=400, system=SUB_SYSTEM, messages=msgs, **kw)
    text = "".join(b.text for b in r.content if b.type == "text").strip()
    read = client.messages.count_tokens(model=model, system=SUB_SYSTEM, messages=msgs).input_tokens
    return text, read
