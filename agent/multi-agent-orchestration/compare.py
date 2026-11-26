"""同一个排障问题，一个 agent 查 vs 主 agent 分诊 + 三个子 agent 并行查。数模型调用、token、主 agent 的上下文多大。

    uv run python multi-agent-orchestration/compare.py single
    uv run python multi-agent-orchestration/compare.py multi
    uv run python multi-agent-orchestration/compare.py multi-good   # 任务书由人写好，主 agent 只汇总

模型默认是本机 llama-server（Qwen3-1.7B）；有 ANTHROPIC_API_KEY 和 MODEL 就连 Claude。
"""

import operator
import re
import sys
import threading
import time
from pathlib import Path
from typing import Annotated, TypedDict

from langchain.agents import create_agent
from langchain_core.callbacks import BaseCallbackHandler
from langchain_core.messages import HumanMessage, SystemMessage
from langchain_core.tools import tool
from langgraph.graph import END, START, StateGraph
from langgraph.types import Send

sys.path.insert(0, str(Path(__file__).parent.parent / "langgraph-basics"))
from tools import QUESTION, SYSTEM, make_model, oncall, parse_gc_log, search_logs, thread_dump  # noqa: E402


@tool
def search_notes(query: str) -> str:
    """在值班知识库（学习笔记）里搜相关经验，返回最多 2 条摘要和页面名。query 写现象关键词，例如 Full GC 老年代。"""
    return oncall.search_notes(query)


# ---------- 记账：每次模型调用算在哪个 agent 头上 ----------
class Ledger(BaseCallbackHandler):
    def __init__(self):
        self.rows, self.who, self.lock = [], {}, threading.Lock()

    def on_chat_model_start(self, serialized, messages, *, run_id, metadata=None, **kw):
        self.who[run_id] = (metadata or {}).get("agent", "?")

    def on_llm_end(self, response, *, run_id, **kw):
        u = response.generations[0][0].message.usage_metadata or {}
        with self.lock:
            self.rows.append((self.who.get(run_id, "?"), u.get("input_tokens", 0), u.get("output_tokens", 0)))


LEDGER = Ledger()
GOOD_BRIEF = False
MODEL = make_model()


def cfg(agent: str) -> dict:
    return {"callbacks": [LEDGER], "metadata": {"agent": agent}, "recursion_limit": 12}


# ---------- 方案 A：一个 agent，四个工具 ----------
def single() -> str:
    agent = create_agent(MODEL, tools=[search_logs, parse_gc_log, thread_dump, search_notes], system_prompt=SYSTEM)
    out = agent.invoke({"messages": [HumanMessage(QUESTION)]}, cfg("single"))
    return out["messages"][-1].content


# ---------- 方案 B：主 agent 分诊 → 三个子 agent 并行 → 主 agent 汇总 ----------
SUB = {
    "logs": ([search_logs, thread_dump], "你是日志调查员。按任务书查 order-svc 的应用日志和线程栈。"),
    "metrics": ([parse_gc_log], "你是指标调查员。按任务书解析 order-svc 的 GC 日志。"),
    "notes": ([search_notes], "你是知识库调查员。按任务书在值班笔记里找相似经验。"),
}
SUB_RULE = "查完只交三句话以内的结论，必须引用工具返回的原文数字；查不到就说查不到，不要猜。"
DEFAULT_BRIEF = {"logs": "查 20:00 以后的 WARN/ERROR、上线记录（关键字 Export|耗时|上线），再看线程栈卡在哪",
                 "metrics": "解析 20:00 以后的 GC 日志，报 Full GC 次数、停顿、回收后堆占用",
                 "notes": "在笔记里找 Full GC、老年代、导出相关的经验"}


class S(TypedDict, total=False):
    briefs: dict
    findings: Annotated[list, operator.add]
    answer: str


def triage(s: S) -> dict:
    if GOOD_BRIEF:                                # 对照组：任务书由人写好（时间窗、关键字都给了）
        for k, b in DEFAULT_BRIEF.items():
            print(f"  任务书[{k}]（人写的）：{b[:60]}")
        return {"briefs": dict(DEFAULT_BRIEF)}
    prompt = (f"{QUESTION}\n你是值班主管，不自己查，派三个调查员：日志、指标、笔记。"
              "每人写一行任务书，写清查什么、从几点开始查（告警前 15 分钟）。严格按格式输出三行：\n"
              "日志：……\n指标：……\n笔记：……")
    text = MODEL.invoke([HumanMessage(prompt)], cfg("main")).content
    briefs = {}
    for key, label in (("logs", "日志"), ("metrics", "指标"), ("notes", "笔记")):
        m = re.search(rf"{label}[:：]\s*(.+)", text)
        briefs[key] = m.group(1).strip() if m else DEFAULT_BRIEF[key]
        print(f"  任务书[{key}]{'' if m else '（模型没按格式写，用默认）'}：{briefs[key][:60]}")
    return {"briefs": briefs}


def fan_out(s: S) -> list[Send]:
    return [Send("investigate", {"agent": k, "brief": b}) for k, b in s["briefs"].items()]


def investigate(task: dict) -> dict:
    tools, role = SUB[task["agent"]]
    sub = create_agent(MODEL, tools=tools, system_prompt=f"{role}{SUB_RULE}")
    out = sub.invoke({"messages": [HumanMessage(f"任务书：{task['brief']}")]}, cfg(task["agent"]))
    raw = sum(len(m.content) for m in out["messages"] if m.type == "tool")
    finding = out["messages"][-1].content
    print(f"  [{task['agent']}] 子 agent 读了工具原文 {raw} 字，交回 {len(finding)} 字")
    return {"findings": [f"【{task['agent']}】{finding}"]}


def summarize(s: S) -> dict:
    msgs = [SystemMessage(SYSTEM), HumanMessage(QUESTION + "\n\n三个调查员的结论：\n" + "\n".join(s["findings"])
                                               + "\n\n请给出最终结论：原因、证据、下一步。")]
    return {"answer": MODEL.invoke(msgs, cfg("main")).content}


def multi() -> str:
    g = StateGraph(S)
    g.add_node("triage", triage)
    g.add_node("investigate", investigate)
    g.add_node("summarize", summarize)
    g.add_edge(START, "triage")
    g.add_conditional_edges("triage", fan_out, ["investigate"])
    g.add_edge("investigate", "summarize")
    g.add_edge("summarize", END)
    return g.compile().invoke({})["answer"]


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "single"
    GOOD_BRIEF = mode == "multi-good"
    t0 = time.perf_counter()
    answer = single() if mode == "single" else multi()
    wall = time.perf_counter() - t0
    print(f"\n=== {mode} 结论 ===\n{answer}\n")
    print(f"{'agent':10}{'调用':>4}{'输入 token':>11}{'输出 token':>11}{'最大一次输入':>12}")
    for who in dict.fromkeys(r[0] for r in LEDGER.rows):
        rs = [r for r in LEDGER.rows if r[0] == who]
        print(f"{who:10}{len(rs):>4}{sum(r[1] for r in rs):>11}{sum(r[2] for r in rs):>11}{max(r[1] for r in rs):>12}")
    tin, tout = sum(r[1] for r in LEDGER.rows), sum(r[2] for r in LEDGER.rows)
    print(f"{'合计':10}{len(LEDGER.rows):>4}{tin:>11}{tout:>11}       用时 {wall:.0f}s")
    print("提到导出接口：", "是" if re.search(r"导出|export|Export", answer) else "否")
