"""全景：一个告警进来以后 —— 主 agent 分诊、三个子 agent 并行调查、人批准后建工单。

值班助手 v3 的骨架，用 LangGraph 写。模型换成了照剧本出牌的函数（MODEL_SECONDS 模拟模型耗时），
这里实测的是框架那一侧：并行扇出、检查点、中断后换一个进程恢复、崩溃重放时写操作只做一次。

    uv run python pano-08-alert-to-ticket/main.py run --flaky notes   # 跑到“等人批准”停下，进程退出
    uv run python pano-08-alert-to-ticket/main.py resume --approve create_ticket,notify --crash
    uv run python pano-08-alert-to-ticket/main.py resume              # 崩溃后再来一次，看工单建了几张
    uv run python pano-08-alert-to-ticket/main.py history             # 列出这条线程的全部检查点
"""

import argparse
import json
import operator
import sqlite3
import sys
import threading
import time
from pathlib import Path
from typing import Annotated, TypedDict

from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, Send, interrupt

sys.path.insert(0, str(Path(__file__).parent.parent / "pano-04-triage-agent"))
import oncall  # noqa: E402  站 04 的工具和 2026-11-08 20:16 那次现场（假数据）

DATA = Path(__file__).parent / "data"
THREAD = {"configurable": {"thread_id": "alert-7f3a"}}   # 线程 id 用告警指纹
ALERT = {"fingerprint": "7f3a", "alertname": "OrderApiP99High", "service": "order-svc",
         "api": "order-04 /orders/{id}", "value": "P99 2.1s", "threshold": "800ms", "startsAt": "20:16"}
MODEL_SECONDS = {"triage": 0.6, "logs": 1.2, "metrics": 0.9, "notes": 0.7, "summarize": 0.8}


class Finding(TypedDict):
    agent: str
    summary: str


class State(TypedDict, total=False):
    alert: dict
    plan: list[dict]
    findings: Annotated[list[Finding], operator.add]   # 三个子 agent 并行写，用 reducer 合并
    conclusion: str
    actions: list[dict]
    approval: dict
    done: Annotated[list[str], operator.add]


def log(msg: str) -> None:
    print(f"{time.perf_counter() - T0:6.2f}s  {msg}", flush=True)


LOCK = threading.Lock()   # 同一步里的子 agent 跑在不同线程上


def count_run(name: str) -> int:
    with LOCK:
        f = DATA / "runs.json"
        runs = json.loads(f.read_text()) if f.exists() else {}
        runs[name] = runs.get(name, 0) + 1
        f.write_text(json.dumps(runs))
        return runs[name]


# ---------- 节点 ----------
def triage(state: State) -> dict:
    """主 agent：读告警，分诊，给三个子 agent 各写一份任务书。"""
    time.sleep(MODEL_SECONDS["triage"])
    plan = [
        {"agent": "logs", "brief": "查 20:00 以后的 WARN/ERROR、上线记录和线程栈，看线程卡在哪"},
        {"agent": "metrics", "brief": "解析 20:00 以后的 GC 日志：Full GC 次数、停顿、回收后占用"},
        {"agent": "notes", "brief": "在值班笔记里找 Full GC、老年代、导出相关的经验"},
    ]
    log(f"triage     分诊：性能类，派 {len(plan)} 个子 agent")
    return {"plan": plan}


def fan_out(state: State) -> list[Send]:
    return [Send("investigate", task) for task in state["plan"]]


def investigate(task: dict) -> dict:
    """子 agent：只拿自己的任务书和自己的工具，返回一小段结论（不是原始日志）。"""
    name = task["agent"]
    n = count_run(name)
    log(f"investigate[{name}] 开始（第 {n} 次执行）")
    time.sleep(MODEL_SECONDS[name])
    if name in FLAKY and n == 1:
        log(f"investigate[{name}] 超时，抛异常")
        raise TimeoutError(f"{name} 子 agent 超时")
    if name == "logs":
        raw = oncall.search_logs("Export|耗时", "20:00") + "\n" + oncall.thread_dump()
        summary = "20:00 上线 /orders/export；导出一次把 412,883 行读进内存；线程卡在 ExportService.loadAll / writeExcel"
    elif name == "metrics":
        raw = oncall.parse_gc_log("20:00")
        summary = raw
    else:
        raw = oncall.search_notes("Full GC 老年代 导出")
        summary = raw.splitlines()[0]
    log(f"investigate[{name}] 完成：原始 {len(raw)} 字 → 结论 {len(summary)} 字")
    return {"findings": [{"agent": name, "summary": summary}]}


def summarize(state: State) -> dict:
    time.sleep(MODEL_SECONDS["summarize"])
    conclusion = ("根因：20:00 上线的按月导出接口一次把 41 万行订单读进内存，老年代打满，"
                  "20:12 起每 1～2 分钟一次约 1.9s 的 Full GC，/orders/{id} 跟着变慢。")
    actions = [
        {"name": "create_ticket", "args": {"title": "order-svc 导出接口引发 Full GC，order-04 P99 2.1s"}},
        {"name": "notify", "args": {"channel": "#order-oncall"}},
        {"name": "throttle_export", "args": {"api": "/orders/export", "qps": 0}},
    ]
    log(f"summarize  汇总 {len(state['findings'])} 份结论，提议 {len(actions)} 个写操作")
    return {"conclusion": conclusion, "actions": actions}


def approve(state: State) -> dict:
    """写操作前停下等人。第一次执行到 interrupt() 就抛出，状态已存进检查点，进程可以直接退出。"""
    decision = interrupt({"conclusion": state["conclusion"], "actions": state["actions"]})
    log(f"approve    {decision['by']} 批准了 {decision['approved']}")
    return {"approval": decision}


def act(state: State, config) -> dict:
    """执行批准过的写操作。幂等键 = 线程 id + 动作名：重放时同一个动作只生效一次。"""
    thread = config["configurable"]["thread_id"]
    count_run("act")
    done = []
    for a in state["actions"]:
        if a["name"] not in state["approval"]["approved"]:
            audit(thread, a, state["approval"]["by"], "未批准，跳过")
            continue
        key = f"{thread}:{a['name']}"
        result = TICKETS.do(key, a)
        audit(thread, a, state["approval"]["by"], result)
        log(f"act        {a['name']} → {result}")
        done.append(a["name"])
        if CRASH and a["name"] == "create_ticket":
            log("act        建完工单、检查点还没存，进程崩了")
            raise SystemExit(3)
    return {"done": done}


# ---------- 假的工单系统：幂等键唯一 ----------
class Tickets:
    def __init__(self, path: Path):
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("CREATE TABLE IF NOT EXISTS ops (idem_key TEXT PRIMARY KEY, result TEXT)")

    def do(self, key: str, action: dict) -> str:
        row = self.db.execute("SELECT result FROM ops WHERE idem_key = ?", (key,)).fetchone()
        if row:
            return f"{row[0]}（幂等键已存在，没有重复执行）"
        n = self.db.execute("SELECT count(*) FROM ops").fetchone()[0]
        result = {"create_ticket": f"已建工单 INC-{1108 + n}", "notify": "已发 #order-oncall",
                  "throttle_export": "已把 /orders/export 限流到 0"}[action["name"]]
        self.db.execute("INSERT INTO ops VALUES (?, ?)", (key, result))
        self.db.commit()
        return result


def audit(thread: str, action: dict, by: str, result: str) -> None:
    with open(DATA / "audit.jsonl", "a") as f:
        f.write(json.dumps({"at": time.strftime("%H:%M:%S"), "thread": thread, "action": action["name"],
                            "args": action["args"], "approved_by": by, "result": result}, ensure_ascii=False) + "\n")


def build(saver):
    g = StateGraph(State)
    g.add_node("triage", triage)
    g.add_node("investigate", investigate)
    g.add_node("summarize", summarize)
    g.add_node("approve", approve)
    g.add_node("act", act)
    g.add_edge(START, "triage")
    g.add_conditional_edges("triage", fan_out, ["investigate"])
    g.add_edge("investigate", "summarize")
    g.add_edge("summarize", "approve")
    g.add_edge("approve", "act")
    g.add_edge("act", END)
    return g.compile(checkpointer=saver)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("cmd", choices=["run", "resume", "history"])
    p.add_argument("--flaky", default="", help="第一次执行会超时的子 agent，逗号分隔")
    p.add_argument("--approve", default=None, help="批准哪些写操作，逗号分隔；不给就是接着跑")
    p.add_argument("--crash", action="store_true", help="建完工单后模拟进程崩溃")
    args = p.parse_args()
    FLAKY, CRASH, T0 = set(filter(None, args.flaky.split(","))), args.crash, time.perf_counter()
    DATA.mkdir(exist_ok=True)
    if args.cmd == "run":
        for f in DATA.glob("*"):
            f.unlink()
    TICKETS = Tickets(DATA / "tickets.db")
    with SqliteSaver.from_conn_string(str(DATA / "checkpoints.db")) as saver:
        graph = build(saver)
        if args.cmd == "history":
            for s in graph.get_state_history(THREAD):
                print(f"step {s.metadata['step']:>2}  next={list(s.next)}  "
                      f"findings={len(s.values.get('findings', []))}  writes={list((s.metadata.get('writes') or {}).keys())}")
            sys.exit()
        if args.cmd == "run":
            try:
                out = graph.invoke({"alert": ALERT}, THREAD)
            except TimeoutError as e:                # --flaky：这一步失败，别的子 agent 的结果已存成 pending writes
                pending = [w[1] for w in saver.get_tuple(THREAD).pending_writes]
                log(f"✗ {e}；检查点里已存的 pending writes：{pending}。重试：graph.invoke(None)")
                out = graph.invoke(None, THREAD)
        else:
            cmd = None if args.approve is None else Command(
                resume={"approved": args.approve.split(","), "by": "值班 张三 20:31"})
            out = graph.invoke(cmd, THREAD)
        if "__interrupt__" in out:
            req = out["__interrupt__"][0].value
            log(f"⏸  停在 approve，等人批准：{[a['name'] for a in req['actions']]}；进程退出")
        snap = graph.get_state(THREAD)
        log(f"线程 alert-7f3a：next={list(snap.next)}，检查点共 {len(list(graph.get_state_history(THREAD)))} 个")
