"""worker：从 Redis Stream 领调查任务，一轮一轮跑 agent 循环，每轮存检查点、推进度。

python worker.py --name A [--crash-at 5] [--no-checkpoint] [--no-idem] [--min-idle-ms 2000]
                          [--heartbeat] [--max-tasks N] [--idle-exit 3]

--crash-at N：第 N 轮工具执行完、检查点还没写时 os._exit(1)，模拟 Pod 被杀。
"""
import argparse
import json
import os
import threading
import time

from common import (DEAD, GROUP, SCRIPT, STREAMS, db, ensure_groups, fake_model, r, run_tool)

MAX_DELIVERIES = 3


def progress(c, incident: str, rnd: int, msg: str) -> None:
    # 进度事件写进每个事件自己的一条流：网页断线重连时，用 Last-Event-ID 从断点接着读
    c.xadd(f"progress:{incident}", {"round": rnd, "msg": msg}, maxlen=200, approximate=True)


def load_state(con, incident: str, use_ckpt: bool):
    if use_ckpt:
        row = con.execute("SELECT round, messages FROM checkpoints WHERE incident=?", (incident,)).fetchone()
        if row:
            return row[0], json.loads(row[1])
    return 0, []


def save_state(con, incident: str, rnd: int, messages: list) -> None:
    con.execute("INSERT INTO checkpoints VALUES(?,?,?,?) ON CONFLICT(incident) DO UPDATE SET round=excluded.round,"
                " messages=excluded.messages, updated=excluded.updated", (incident, rnd, json.dumps(messages), time.time()))


def investigate(c, con, stream: str, msg_id: str, fields: dict, a) -> None:
    incident, prio = fields["incident"], fields["priority"]
    done_rounds, messages = load_state(con, incident, not a.no_checkpoint)
    if done_rounds:
        print(f"[{a.name}] {incident} 从检查点恢复：已完成 {done_rounds} 轮，从第 {done_rounds + 1} 轮接着跑", flush=True)
    stop = threading.Event()
    if a.heartbeat:  # 心跳：定期 XCLAIM 自己手上的消息（JUSTID），把空闲时间清零，别人就认领不走
        def beat():
            c2 = r()
            while not stop.wait(a.min_idle_ms / 4000):
                c2.xclaim(stream, GROUP, a.name, 0, [msg_id], justid=True)
        threading.Thread(target=beat, daemon=True).start()
    try:
        for rnd in range(done_rounds + 1, len(SCRIPT) + 1):
            decision = fake_model(con, incident, rnd, a.name)
            if decision["type"] == "final":
                messages.append({"round": rnd, "final": decision["text"]})
                save_state(con, incident, rnd, messages)
                progress(c, incident, rnd, "报告已出")
                break
            result = run_tool(con, incident, decision["tool"], decision["args"], a.name, not a.no_idem)
            if a.crash_at == rnd:
                print(f"[{a.name}] 第 {rnd} 轮工具 {decision['tool']} 已执行，检查点还没写 —— 进程被杀", flush=True)
                os._exit(1)
            messages.append({"round": rnd, "tool": decision["tool"], "result": result})
            save_state(con, incident, rnd, messages)
            progress(c, incident, rnd, f"第 {rnd}/6 轮：{decision['tool']}")
    finally:
        stop.set()
    con.execute("INSERT INTO done VALUES(?,?,?,?)", (incident, prio, a.name, time.time()))
    c.xack(stream, GROUP, msg_id)   # 做完才确认；先确认再做，崩了任务就丢了


def next_task(c, a):
    # 1) 先捡别人崩掉留下的：空闲超过 min-idle 的待确认消息
    for s in STREAMS:
        _, entries, _ = c.xautoclaim(s, GROUP, a.name, a.min_idle_ms, start_id="0-0", count=1)
        for msg_id, fields in entries:
            if fields is None:
                continue
            pend = c.xpending_range(s, GROUP, min=msg_id, max=msg_id, count=1)
            if pend and pend[0]["times_delivered"] > MAX_DELIVERIES:   # 毒消息：投了 3 次还没做完
                c.xadd(DEAD, {**fields, "from": s})
                c.xack(s, GROUP, msg_id)
                continue
            print(f"[{a.name}] 认领了 {fields['incident']}（{s}，第 {pend[0]['times_delivered'] if pend else '?'} 次投递）", flush=True)
            return s, msg_id, fields
    # 2) 再按优先级领新的：p0 空了才看 p1
    for s in STREAMS:
        got = c.xreadgroup(GROUP, a.name, {s: ">"}, count=1)
        if got:
            msg_id, fields = got[0][1][0]
            return s, msg_id, fields
    got = c.xreadgroup(GROUP, a.name, {s: ">" for s in STREAMS}, count=1, block=500)
    if got:
        s = got[0][0]
        msg_id, fields = got[0][1][0]
        return s, msg_id, fields
    return None


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--name", required=True)
    p.add_argument("--crash-at", type=int, default=0)
    p.add_argument("--no-checkpoint", action="store_true")
    p.add_argument("--no-idem", action="store_true")
    p.add_argument("--heartbeat", action="store_true")
    p.add_argument("--min-idle-ms", type=int, default=2000)
    p.add_argument("--max-tasks", type=int, default=0)
    p.add_argument("--idle-exit", type=float, default=3.0, help="连续这么多秒没活就退出")
    a = p.parse_args()
    c, con = r(), db()
    ensure_groups(c)
    n, idle_since = 0, time.time()
    while True:
        t = next_task(c, a)
        if t is None:
            if time.time() - idle_since > a.idle_exit:
                break
            continue
        investigate(c, con, *t, a)
        n, idle_since = n + 1, time.time()
        if a.max_tasks and n >= a.max_tasks:
            break


if __name__ == "__main__":
    main()
