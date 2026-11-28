"""长任务架构的公共部分：Redis 连接、SQLite 状态库、假模型和假工具。

没有真模型：fake_model() 按固定剧本给出 6 轮决定（前 4 轮查东西，第 5 轮建工单，第 6 轮出报告），
每轮睡 MODEL_SEC 秒，并把“调了一次模型”记进 SQLite，方便跨进程数次数。
"""
import hashlib
import json
import os
import sqlite3
import time

import redis

REDIS_URL = os.environ.get("REDIS_URL", "redis://127.0.0.1:6379/0")
DB_PATH = os.environ.get("STATE_DB", os.path.join(os.path.dirname(__file__), "state.db"))
MODEL_SEC = float(os.environ.get("MODEL_SEC", "0.5"))
STREAMS = ["oncall:p0", "oncall:p1", "oncall:p2"]   # 优先级从高到低
GROUP = "investigators"
DEAD = "oncall:dead"

# 剧本：每轮模型“决定”调哪个工具；第 6 轮给出结论
SCRIPT = ["search_logs", "parse_gc_log", "thread_dump", "search_notes", "create_ticket", None]


def r() -> redis.Redis:
    return redis.Redis.from_url(REDIS_URL, decode_responses=True)


def db() -> sqlite3.Connection:
    con = sqlite3.connect(DB_PATH, timeout=10, isolation_level=None)
    con.execute("PRAGMA journal_mode=WAL")
    con.executescript("""
    CREATE TABLE IF NOT EXISTS checkpoints(incident TEXT PRIMARY KEY, round INT, messages TEXT, updated REAL);
    CREATE TABLE IF NOT EXISTS model_calls(incident TEXT, round INT, worker TEXT, at REAL);
    CREATE TABLE IF NOT EXISTS tickets(id INTEGER PRIMARY KEY, incident TEXT, idem_key TEXT UNIQUE, worker TEXT);
    CREATE TABLE IF NOT EXISTS done(incident TEXT, priority TEXT, worker TEXT, at REAL);
    """)
    return con


def reset_all() -> None:
    """清空 Redis 里的实验数据和 SQLite 状态库。"""
    c = r()
    for k in c.scan_iter("oncall:*"):
        c.delete(k)
    for pat in ("fp:*", "inc:*", "progress:*"):
        for k in c.scan_iter(pat):
            c.delete(k)
    if os.path.exists(DB_PATH):
        for suffix in ("", "-wal", "-shm"):
            if os.path.exists(DB_PATH + suffix):
                os.remove(DB_PATH + suffix)


def ensure_groups(c: redis.Redis) -> None:
    for s in STREAMS:
        try:
            c.xgroup_create(s, GROUP, id="0", mkstream=True)
        except redis.ResponseError as e:
            if "BUSYGROUP" not in str(e):
                raise


def incident_key(alert: dict) -> str:
    """合并键：同一租户、同一服务、同一告警名，5 分钟一个窗口。"""
    lb = alert["labels"]
    window = int(alert.get("startsAt_epoch", time.time()) // 300)
    return f'{alert["tenant"]}|{lb["service"]}|{lb["alertname"]}|{window}'


def idem_key(incident: str, tool: str, args: dict) -> str:
    """写操作的幂等键：同一个事件、同一个工具、同样的参数，只算一次。"""
    raw = incident + "|" + tool + "|" + json.dumps(args, sort_keys=True)
    return hashlib.sha256(raw.encode()).hexdigest()[:16]


def fake_model(con: sqlite3.Connection, incident: str, rnd: int, worker: str) -> dict:
    """第 rnd 轮（从 1 数）的模型决定。睡 MODEL_SEC 秒，记一次调用。"""
    time.sleep(MODEL_SEC)
    con.execute("INSERT INTO model_calls VALUES(?,?,?,?)", (incident, rnd, worker, time.time()))
    tool = SCRIPT[rnd - 1]
    if tool is None:
        return {"type": "final", "text": f"{incident}：GC 停顿导致 P99 升高，证据见第 1～4 轮"}
    args = {"incident": incident} if tool == "create_ticket" else {"q": incident.split("-")[1]}
    return {"type": "tool_use", "tool": tool, "args": args}


def run_tool(con: sqlite3.Connection, incident: str, tool: str, args: dict, worker: str, idempotent: bool) -> str:
    time.sleep(0.05)
    if tool != "create_ticket":
        return f"{tool} ok"
    key = idem_key(incident, tool, args) if idempotent else None
    cur = con.execute("INSERT OR IGNORE INTO tickets(incident, idem_key, worker) VALUES(?,?,?)", (incident, key, worker))
    return "ticket created" if cur.rowcount else "ticket already exists (same idem_key)"
