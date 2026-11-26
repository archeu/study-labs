"""长任务的检查点会长多大：一个 agent 跑 N 轮，每轮往 messages 里加一条约 600 字节的消息。
对比默认的 reducer（每个检查点存整份列表）和 1.2 里的 DeltaChannel（beta，只存改动，隔一段存一次全量）。

    uv run python langgraph-checkpoint/growth.py
"""

import operator
import sqlite3
import tempfile
import time
from pathlib import Path
from typing import Annotated, TypedDict

from langgraph.channels import DeltaChannel
from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import END, START, StateGraph

MSG = "20:12:45 WARN order-svc OrderController /orders/{id} 耗时 2104ms " * 8   # 约 600 字节


def append(state: list | None, writes: list[list]) -> list:
    """DeltaChannel 的 reducer：收到一批写入，一次合并。要求分批合和一次合结果一样。"""
    return (state or []) + [m for w in writes for m in w]


class Full(TypedDict):
    n: int
    messages: Annotated[list, operator.add]


class Delta(TypedDict):
    n: int
    messages: Annotated[list, DeltaChannel(append)]


def run(schema, rounds: int) -> dict:
    g = StateGraph(schema)
    g.add_node("turn", lambda s: {"n": s["n"] + 1, "messages": [f"{s['n']}:{MSG}"]})
    g.add_edge(START, "turn")
    g.add_conditional_edges("turn", lambda s: "turn" if s["n"] < rounds else END)
    db = Path(tempfile.mkdtemp()) / "cp.db"
    cfg = {"configurable": {"thread_id": "t"}, "recursion_limit": rounds + 5}
    with SqliteSaver.from_conn_string(str(db)) as saver:
        graph = g.compile(checkpointer=saver)
        t0 = time.perf_counter()
        graph.invoke({"n": 0, "messages": []}, cfg)
        run_s = time.perf_counter() - t0
        t0 = time.perf_counter()
        n_msgs = len(graph.get_state(cfg).values["messages"])      # 恢复：读回最新 state
        load_ms = (time.perf_counter() - t0) * 1000
    con = sqlite3.connect(db)
    cp_bytes, cp_rows = con.execute("SELECT sum(length(checkpoint)), count(*) FROM checkpoints").fetchone()
    last = con.execute("SELECT length(checkpoint) FROM checkpoints ORDER BY checkpoint_id DESC LIMIT 1").fetchone()[0]
    w_bytes = con.execute("SELECT sum(length(value)) FROM writes").fetchone()[0]
    return {"rows": cp_rows, "cp_kb": cp_bytes / 1024, "last_kb": last / 1024, "w_kb": w_bytes / 1024,
            "run_s": run_s, "load_ms": load_ms, "msgs": n_msgs}


if __name__ == "__main__":
    print(f"{'reducer':10}{'轮数':>6}{'检查点行':>8}{'checkpoints 表':>16}{'最新一行':>10}{'writes 表':>11}{'跑完':>8}{'读回 state':>11}")
    for rounds in (50, 200):
        for name, schema in (("默认", Full), ("Delta", Delta)):
            r = run(schema, rounds)
            print(f"{name:10}{rounds:>6}{r['rows']:>8}{r['cp_kb']:>13.0f} KB{r['last_kb']:>7.1f} KB{r['w_kb']:>8.0f} KB"
                  f"{r['run_s']:>7.2f}s{r['load_ms']:>8.1f} ms   （读回 {r['msgs']} 条）")
