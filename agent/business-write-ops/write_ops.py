"""写操作的四道关：提议 → 审批（interrupt，带格式校验）→ 带幂等键执行 → 审计。三个剧本：

    uv run python business-write-ops/write_ops.py replay     # 建完工单崩溃，重跑
    uv run python business-write-ops/write_ops.py twice      # 同一个批准被投递两次，两个 worker 同时恢复
    uv run python business-write-ops/write_ops.py badapprove # 批准里带了清单外的动作

每个剧本都分别用“先查后写”和“唯一约束”两种工单库跑一遍。不调模型。
"""

import hashlib
import json
import sqlite3
import sys
import tempfile
import threading
import time
from pathlib import Path
from typing import Literal, TypedDict

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt

ACTIONS = [
    {"name": "create_ticket", "args": {"title": "order-svc 导出接口引发 Full GC，order-04 P99 2.1s"}},
    {"name": "notify", "args": {"channel": "#order-oncall"}},
    {"name": "throttle_export", "args": {"api": "/orders/export", "qps": 0}},
]


class Approval(TypedDict):                      # 用 TypedDict：校验完是普通 dict，存进检查点不触发“未登记类型”的警告
    approved: list[Literal["create_ticket", "notify", "throttle_export"]]
    by: str


class S(TypedDict, total=False):
    fingerprint: str
    approval: dict
    done: list


# ---------- 下游：工单系统 + 审计表 ----------
class Downstream:
    def __init__(self, mode: str):
        self.mode, self.path = mode, Path(tempfile.mkdtemp()) / "ops.db"
        db = sqlite3.connect(self.path)
        key = "idem_key TEXT PRIMARY KEY" if mode == "unique" else "idem_key TEXT"   # 先查后写：没有唯一约束
        db.execute(f"CREATE TABLE tickets ({key}, no TEXT, title TEXT)")
        db.execute("CREATE TABLE audit (id INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT, worker TEXT, thread TEXT,"
                   " action TEXT, args TEXT, approved_by TEXT, idem_key TEXT, result TEXT)")
        db.commit()

    def create_ticket(self, key: str, title: str) -> str:
        db = sqlite3.connect(self.path, timeout=5, isolation_level=None)
        if self.mode == "check-then-act":
            row = db.execute("SELECT no FROM tickets WHERE idem_key = ?", (key,)).fetchone()
            if row:
                return f"{row[0]}（已存在，没再建）"
            time.sleep(0.2)                                           # 查完到写之间，另一个 worker 也查完了
            no = f"INC-{1108 + db.execute('SELECT count(*) FROM tickets').fetchone()[0]}"
            db.execute("INSERT INTO tickets VALUES (?, ?, ?)", (key, no, title))
            return f"已建 {no}"
        # 唯一约束：插入本身就是判断，数据库保证同一个键只有一行
        no = f"INC-{1108 + db.execute('SELECT count(*) FROM tickets').fetchone()[0]}"
        cur = db.execute("INSERT OR IGNORE INTO tickets VALUES (?, ?, ?)", (key, no, title))
        got = db.execute("SELECT no FROM tickets WHERE idem_key = ?", (key,)).fetchone()[0]
        return f"已建 {got}" if cur.rowcount == 1 else f"{got}（幂等键已存在，没再建）"

    def audit(self, **row) -> None:
        db = sqlite3.connect(self.path, timeout=5, isolation_level=None)
        db.execute("INSERT INTO audit (at, worker, thread, action, args, approved_by, idem_key, result) VALUES (?,?,?,?,?,?,?,?)",
                   (time.strftime("%H:%M:%S"), row["worker"], row["thread"], row["action"], json.dumps(row["args"], ensure_ascii=False),
                    row["approved_by"], row["idem_key"], row["result"]))

    def count(self, table: str) -> int:
        return sqlite3.connect(self.path).execute(f"SELECT count(*) FROM {table}").fetchone()[0]

    def audit_rows(self) -> list:
        return sqlite3.connect(self.path).execute("SELECT worker, action, idem_key, result FROM audit ORDER BY id").fetchall()


def idem_key(fp: str, action: dict) -> str:
    """业务键：告警指纹 + 动作 + 参数摘要。同一个意图永远是同一个键，换一组参数就是新意图。"""
    digest = hashlib.sha256(json.dumps(action["args"], sort_keys=True, ensure_ascii=False).encode()).hexdigest()[:8]
    return f"{fp}:{action['name']}:{digest}"


def build(down: Downstream, crash: dict):
    def approve(s: S) -> dict:
        d = interrupt({"actions": ACTIONS}, response_schema=Approval)     # 格式不对，resume 时直接报错
        return {"approval": d}

    def act(s: S, config) -> dict:
        worker = config["configurable"].get("worker", "w1")
        thread = config["configurable"]["thread_id"]
        done = []
        for a in ACTIONS:
            key = idem_key(s["fingerprint"], a)
            if a["name"] not in s["approval"]["approved"]:
                down.audit(worker=worker, thread=thread, action=a["name"], args=a["args"],
                           approved_by=s["approval"]["by"], idem_key=key, result="未批准，跳过")
                continue
            result = down.create_ticket(key, a["args"].get("title", a["name"])) if a["name"] == "create_ticket" else f"{a['name']} 完成"
            down.audit(worker=worker, thread=thread, action=a["name"], args=a["args"],
                       approved_by=s["approval"]["by"], idem_key=key, result=result)
            done.append(a["name"])
            if crash.pop("after_ticket", False) and a["name"] == "create_ticket":
                raise RuntimeError("建完工单、检查点还没存，进程崩了")
        return {"done": done}

    g = StateGraph(S)
    g.add_node("approve", approve)
    g.add_node("act", act)
    g.add_edge(START, "approve")
    g.add_edge("approve", "act")
    g.add_edge("act", END)
    return g.compile(checkpointer=InMemorySaver())


OK = {"approved": ["create_ticket", "notify"], "by": "张三 20:31"}


def run(scenario: str, mode: str) -> None:
    down, crash = Downstream(mode), {}
    graph = build(down, crash)
    cfg = {"configurable": {"thread_id": "alert-7f3a"}}
    graph.invoke({"fingerprint": "7f3a"}, cfg)                         # 停在 approve
    if scenario == "replay":
        crash["after_ticket"] = True
        try:
            graph.invoke(Command(resume=OK), cfg)
        except RuntimeError as e:
            print(f"    第 1 次：{e}")
        graph.invoke(None, cfg)                                         # 接着跑：act 整个重跑
        print("    第 2 次：接着跑完")
    elif scenario == "twice":
        gate = threading.Barrier(2)

        def worker(name: str) -> None:
            gate.wait()                                                  # 两个 worker 同一刻收到同一个批准
            graph.invoke(Command(resume=OK), {"configurable": {"thread_id": "alert-7f3a", "worker": name}})
        ts = [threading.Thread(target=worker, args=(n,)) for n in ("w1", "w2")]
        [t.start() for t in ts]
        [t.join() for t in ts]
    else:
        try:
            graph.invoke(Command(resume={"approved": ["create_ticket", "restart_db"], "by": "张三"}), cfg)
        except Exception as e:
            print(f"    批准里带了 restart_db：{type(e).__name__}，线程仍停在 {list(graph.get_state(cfg).next)}")
        graph.invoke(Command(resume=OK), cfg)
        print(f"    改成合法的批准后：next={list(graph.get_state(cfg).next)}")
    print(f"    工单 {down.count('tickets')} 张，审计 {down.count('audit')} 行：")
    for w, action, key, result in down.audit_rows():
        print(f"      {w}  {action:16} {key:34} {result}")


if __name__ == "__main__":
    scenario = sys.argv[1] if len(sys.argv) > 1 else "twice"
    for mode in ("check-then-act", "unique"):
        print(f"[{scenario}] 工单库用 {mode}")
        run(scenario, mode)
