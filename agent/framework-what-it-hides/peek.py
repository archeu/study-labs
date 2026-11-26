"""掀开 LangGraph 的盖子：五件框架替你做了、但你在代码里看不见的事。

    uv run python framework-what-it-hides/peek.py

不调模型，每一段几秒内跑完。对应页面⑤。
"""

import tempfile
import threading
import time
from pathlib import Path
from typing import Annotated, TypedDict
import operator

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.errors import GraphRecursionError
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, Send, interrupt


class S(TypedDict, total=False):
    n: int
    log: Annotated[list[str], operator.add]


def cfg(tid: str) -> dict:
    return {"configurable": {"thread_id": tid}}


# ---------- 1. 并行的节点跑在线程池里 ----------
def part1() -> None:
    print("① 同一步里的三个节点，各在哪个线程上跑")
    shared = {"n": 0}

    def worker(task: dict) -> dict:
        for _ in range(200):                       # 读-改-写同一个计数，不加锁
            n = shared["n"]
            time.sleep(0.0001)                     # 模拟中间做了点别的事（读文件、调接口）
            shared["n"] = n + 1
        return {"log": [f"{task['name']}@{threading.current_thread().name}"]}

    g = StateGraph(S)
    g.add_node("fan", lambda s: {})
    g.add_node("worker", worker)
    g.add_edge(START, "fan")
    g.add_conditional_edges("fan", lambda s: [Send("worker", {"name": x}) for x in ("logs", "metrics", "notes")], ["worker"])
    g.add_edge("worker", END)
    out = g.compile().invoke({})
    print("   ", " | ".join(out["log"]))
    print(f"    三个节点各把计数加 200 次，期望 600，实际 {shared['n']}")


# ---------- 2. 什么时候存盘：durability ----------
class CountingSaver(InMemorySaver):
    def __init__(self):
        super().__init__()
        self.n_put = self.n_writes = 0   # 别叫 writes：InMemorySaver 自己有这个属性

    def put(self, *a, **k):
        self.n_put += 1
        return super().put(*a, **k)

    def put_writes(self, *a, **k):
        self.n_writes += 1
        return super().put_writes(*a, **k)


def linear(crash_at: int | None):
    def step(i):
        def f(s: S) -> dict:
            if i == crash_at:
                raise RuntimeError(f"step{i} 崩了")
            return {"n": i, "log": [f"step{i}"]}
        return f
    g = StateGraph(S)
    for i in range(1, 6):
        g.add_node(f"step{i}", step(i))
    g.add_edge(START, "step1")
    for i in range(1, 5):
        g.add_edge(f"step{i}", f"step{i + 1}")
    g.add_edge("step5", END)
    return g


def part2() -> None:
    print("② 五个节点串成一条线，三种 durability 各存了几次盘；第 4 个节点崩了能从哪接着跑")
    for mode in ("sync", "async", "exit"):
        saver = CountingSaver()
        linear(None).compile(checkpointer=saver).invoke({"n": 0}, cfg("ok"), durability=mode)
        ok = (saver.n_put, saver.n_writes)
        saver = CountingSaver()
        graph = linear(4).compile(checkpointer=saver)
        try:
            graph.invoke({"n": 0}, cfg("crash"), durability=mode)
        except RuntimeError:
            pass
        snap = graph.get_state(cfg("crash"))
        print(f"    {mode:5}  跑完：put {ok[0]} 次、put_writes {ok[1]} 次 | 第 4 步崩：库里 n={snap.values.get('n')}，next={list(snap.next)}")


def killed_child(mode: str, db: str) -> None:
    """子进程：第 4 个节点里直接 os._exit，模拟 kill -9，框架没有机会收尾。"""
    import os
    from langgraph.checkpoint.sqlite import SqliteSaver

    def step(i):
        def f(s: S) -> dict:
            if i == 4:
                time.sleep(0.2)                     # 第 4 步干了 0.2 秒活，然后进程被杀
                os._exit(9)
            return {"n": i}
        return f
    g = StateGraph(S)
    for i in range(1, 6):
        g.add_node(f"step{i}", step(i))
    g.add_edge(START, "step1")
    for i in range(1, 5):
        g.add_edge(f"step{i}", f"step{i + 1}")
    g.add_edge("step5", END)
    with SqliteSaver.from_conn_string(db) as saver:
        g.compile(checkpointer=saver).invoke({"n": 0}, cfg("kill"), durability=mode)


def part2b() -> None:
    import subprocess, sys
    from langgraph.checkpoint.sqlite import SqliteSaver
    print("②b 同样五步，第 4 步时进程被直接杀掉（os._exit，像 kill -9），重启后库里有什么")
    for mode in ("sync", "async", "exit"):
        db = str(Path(tempfile.mkdtemp()) / "cp.db")
        code = subprocess.run([sys.executable, __file__, "--child", mode, db]).returncode
        with SqliteSaver.from_conn_string(db) as saver:
            snap = linear(None).compile(checkpointer=saver).get_state(cfg("kill"))
        print(f"    {mode:5}  进程退出码 {code}，库里 n={snap.values.get('n')}，next={list(snap.next)}")


# ---------- 3. 恢复时，节点从头再执行一遍 ----------
def part3() -> None:
    print("③ interrupt() 前面有一句副作用，批准后它执行了几次")
    sent = []

    def approve(s: S) -> dict:
        sent.append("通知")                         # 副作用写在 interrupt 前面
        ok = interrupt("要建工单吗？")
        return {"log": [f"批准={ok}"]}

    g = StateGraph(S)
    g.add_node("approve", approve)
    g.add_edge(START, "approve")
    g.add_edge("approve", END)
    graph = g.compile(checkpointer=InMemorySaver())
    graph.invoke({}, cfg("t3"))
    print(f"    停下时：通知发了 {len(sent)} 次")
    graph.invoke(Command(resume=True), cfg("t3"))
    print(f"    批准后：通知发了 {len(sent)} 次")


# ---------- 4. 停不下来的循环，框架默认放它跑多少步 ----------
def part4() -> None:
    print("④ 两个节点互相指，没有出口：默认多少步才报错")
    g = StateGraph(S)
    g.add_node("think", lambda s: {"n": s.get("n", 0) + 1})
    g.add_node("act", lambda s: {})
    g.add_edge(START, "think")
    g.add_edge("think", "act")
    g.add_edge("act", "think")
    graph = g.compile()
    for limit in (None, 25):
        t0, steps = time.perf_counter(), 0
        try:
            for chunk in graph.stream({"n": 0}, {"recursion_limit": limit} if limit else {}, stream_mode="updates"):
                steps += 1
        except GraphRecursionError:
            pass
        print(f"    recursion_limit={limit or '默认'}：跑了 {steps} 步才停，用时 {time.perf_counter() - t0:.2f}s")


# ---------- 5. 你写了几行，框架替你做了几件事 ----------
def part5() -> None:
    print("⑤ 全景那张图（分诊 → 3 个并行子 agent → 汇总 → 等批准 → 执行），框架替你做的事")
    saver = CountingSaver()
    calls = []

    def node(name):
        def f(s):
            calls.append(name)
            if name == "approve":
                interrupt("批准？")
            return {"log": [name]}
        return f

    g = StateGraph(S)
    for name in ("triage", "investigate", "summarize", "approve", "act"):
        g.add_node(name, node(name))
    g.add_edge(START, "triage")
    g.add_conditional_edges("triage", lambda s: [Send("investigate", {}) for _ in range(3)], ["investigate"])
    g.add_edge("investigate", "summarize")
    g.add_edge("summarize", "approve")
    g.add_edge("approve", "act")
    g.add_edge("act", END)
    graph = g.compile(checkpointer=saver)
    graph.invoke({}, cfg("t5"))
    graph.invoke(Command(resume=True), cfg("t5"))
    hist = list(graph.get_state_history(cfg("t5")))
    print(f"    节点函数被调用 {len(calls)} 次：{calls}")
    print(f"    存检查点 put {saver.n_put} 次，存中间结果 put_writes {saver.n_writes} 次，历史里能回看 {len(hist)} 个版本")


if __name__ == "__main__":
    import sys
    if sys.argv[1:2] == ["--child"]:
        killed_child(sys.argv[2], sys.argv[3])
        sys.exit()
    for p in (part1, part2, part2b, part3, part4, part5):
        p()
        print()
