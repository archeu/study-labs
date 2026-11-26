"""交接（handoff）和主从（supervisor）的区别：控制权交出去就不回来 vs 派出去还要收回来。不调模型，几毫秒跑完。

    uv run python multi-agent-orchestration/handoff.py
"""

import operator
from typing import Annotated, Literal, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.types import Command


class S(TypedDict, total=False):
    alert: str
    messages: Annotated[list[str], operator.add]   # 交接时整段对话跟着走


def triage(s: S) -> Command[Literal["jvm_agent", "db_agent"]]:
    """前台分诊：判断归谁管，然后把控制权连同对话一起交出去。"""
    target = "db_agent" if "慢查询" in s["alert"] else "jvm_agent"
    return Command(goto=target, update={"messages": [f"triage：这是 {target} 的活，交给它，我不再管"]})


def jvm_agent(s: S) -> Command[Literal["db_agent", "__end__"]]:
    """JVM 专家接手：它看得到前面全部对话；发现其实是数据库的事，可以再交出去。"""
    seen = len(s["messages"])
    if "orders 表" in s["alert"]:
        return Command(goto="db_agent", update={"messages": [f"jvm_agent：看了前 {seen} 条，GC 正常，像是数据库，再交给 db_agent"]})
    return Command(goto=END, update={"messages": [f"jvm_agent：看了前 {seen} 条，Full GC 3 次，结论由我直接回复值班人"]})


def db_agent(s: S) -> dict:
    return {"messages": [f"db_agent：看了前 {len(s['messages'])} 条，orders 表缺索引，结论由我直接回复值班人"]}


g = StateGraph(S)
g.add_node("triage", triage)
g.add_node("jvm_agent", jvm_agent)
g.add_node("db_agent", db_agent)
g.add_edge(START, "triage")
g.add_edge("db_agent", END)
graph = g.compile()

if __name__ == "__main__":
    for alert in ("order-04 P99 2.1s，Full GC 频繁", "order-04 P99 2.1s，GC 正常，orders 表在报警"):
        print(f"告警：{alert}")
        path = [list(step)[0] for step in graph.stream({"alert": alert, "messages": []}, stream_mode="updates")]
        out = graph.invoke({"alert": alert, "messages": []})
        print("  走过的节点：", " → ".join(path))
        for m in out["messages"]:
            print("   ", m)
    print("\n图上的边（Command 的类型标注让框架知道能去哪）：")
    print(" ", sorted({(e.source, e.target) for e in graph.get_graph().edges}))
