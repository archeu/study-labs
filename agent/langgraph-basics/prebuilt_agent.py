"""同一个 agent，用 LangChain 1.x 的 create_agent 一行建出来（它内部也是一张 LangGraph 图）。

    uv run python langgraph-basics/prebuilt_agent.py
"""

import time

from langchain.agents import create_agent

from tools import QUESTION, SYSTEM, TOOLS, make_model

agent = create_agent(make_model(), tools=TOOLS, system_prompt=SYSTEM)

if __name__ == "__main__":
    print("图里的节点：", list(agent.get_graph().nodes))
    t0 = time.perf_counter()
    out = agent.invoke({"messages": [("user", QUESTION)]}, {"recursion_limit": 12})
    for m in out["messages"]:
        extra = f" tool_calls={[c['name'] for c in m.tool_calls]}" if getattr(m, "tool_calls", None) else ""
        print(f"  {m.type:6} {len(str(m.content)):5} 字{extra}")
    print(f"共 {len(out['messages'])} 条消息，用时 {time.perf_counter() - t0:.1f}s")
