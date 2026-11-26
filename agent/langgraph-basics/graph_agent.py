"""站 04 的手写循环，用 LangGraph 的 StateGraph 重写：两个节点、一条条件边、一个检查点。

    uv run python langgraph-basics/graph_agent.py           # 跑一次排障提问，打印每一步
    uv run python langgraph-basics/graph_agent.py --draw    # 只打印图的结构（Mermaid）
"""

import sys
import time

from langchain_core.messages import SystemMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, MessagesState, StateGraph
from langgraph.prebuilt import ToolNode, tools_condition

from tools import QUESTION, SYSTEM, TOOLS, make_model

model = make_model().bind_tools(TOOLS)          # 工具说明书跟着每次请求发给模型


def call_model(state: MessagesState) -> dict:
    """模型节点：读整段对话，返回一条 AIMessage（可能带 tool_calls）。"""
    reply = model.invoke([SystemMessage(SYSTEM)] + state["messages"])
    return {"messages": [reply]}               # add_messages 这个 reducer 会把它追加到列表末尾


builder = StateGraph(MessagesState)
builder.add_node("model", call_model)
builder.add_node("tools", ToolNode(TOOLS))     # 按 tool_calls 调函数，结果包成 ToolMessage
builder.add_edge(START, "model")
builder.add_conditional_edges("model", tools_condition)   # 有 tool_calls 去 tools，没有就 END
builder.add_edge("tools", "model")
graph = builder.compile(checkpointer=InMemorySaver())

if __name__ == "__main__":
    if "--draw" in sys.argv:
        print(graph.get_graph().draw_mermaid())
        sys.exit()
    cfg = {"configurable": {"thread_id": "alert-7f3a"}, "recursion_limit": 12}
    t0 = time.perf_counter()
    for step in graph.stream({"messages": [("user", QUESTION)]}, cfg, stream_mode="updates"):
        for node, upd in step.items():
            msg = upd["messages"][-1]
            if node == "model" and msg.tool_calls:
                calls = [f"{c['name']}({', '.join(f'{k}={v!r}' for k, v in c['args'].items())})" for c in msg.tool_calls]
                print(f"{time.perf_counter() - t0:6.1f}s  model → 要调 {calls}")
            elif node == "model":
                print(f"{time.perf_counter() - t0:6.1f}s  model → 结论：\n{msg.content}")
            else:
                for m in upd["messages"]:
                    print(f"{time.perf_counter() - t0:6.1f}s  tools → {m.name} 返回 {len(m.content)} 字")
    snap = graph.get_state(cfg)
    print(f"\n共 {len(snap.values['messages'])} 条消息，检查点 {len(list(graph.get_state_history(cfg)))} 个，"
          f"用时 {time.perf_counter() - t0:.1f}s")
