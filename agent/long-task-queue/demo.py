"""把页面上的几个实验连起来跑一遍：

1. 1000 条告警打进接入服务：去重、合并成 87 个事件，看 202 回得多快
2. 4 个 worker 按优先级做完 87 个调查：P0 是不是先做完
3. worker 在第 5 轮被杀：有没有检查点、工具幂等不幂等，各花几次模型调用、建了几张工单
4. min-idle 比一轮还短：同一个调查会不会被两个 worker 同时做；加心跳以后呢
5. SSE 断线重连：带 Last-Event-ID 回来，进度一条不丢、不重

REDIS_URL=redis://127.0.0.1:63710/0 uv run python long-task-queue/demo.py
"""
import os
import random
import statistics
import subprocess
import sys
import time

import httpx

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from common import STREAMS, db, ensure_groups, r, reset_all  # noqa: E402

PORT = 18710
BASE = f"http://127.0.0.1:{PORT}"


def make_alerts(seed=7):
    """87 个事件（交易 52 / 支付 23 / 搜索 12；P0 9 / P1 31 / P2 47），880 条不同的告警，再加 120 条重发。"""
    rnd = random.Random(seed)
    tenants = ["trade"] * 52 + ["pay"] * 23 + ["search"] * 12
    sev = ["critical"] * 9 + ["warning"] * 31 + ["info"] * 47
    rnd.shuffle(sev)
    now = 1794398400  # 2026-11-11 20:00 +08:00
    alerts = []
    for i in range(87):
        hosts = 11 if i < 10 else 10
        for h in range(hosts):
            alerts.append({"tenant": tenants[i], "fingerprint": f"{i:02d}{h:02d}ab",
                           "startsAt_epoch": now + rnd.randint(0, 240),
                           "labels": {"alertname": f"Alert{i:02d}", "service": f"svc{i:02d}",
                                      "instance": f"host-{h}", "severity": sev[i]}})
    alerts += [dict(rnd.choice(alerts)) for _ in range(120)]  # Alertmanager 重发的同一条
    rnd.shuffle(alerts)
    # startsAt 落在同一个 5 分钟窗口里，合并键才相同；把窗口对齐到 20:00
    for a in alerts:
        a["startsAt_epoch"] = now
    return alerts


def start_ingress():
    p = subprocess.Popen([sys.executable, "-m", "uvicorn", "ingress:app", "--port", str(PORT), "--log-level", "warning"],
                         cwd=HERE, env=os.environ.copy())
    for _ in range(50):
        try:
            httpx.get(BASE + "/docs", timeout=0.5)
            return p
        except httpx.HTTPError:
            time.sleep(0.2)
    raise SystemExit("ingress did not start")


def workers(specs, extra_env=None):
    env = {**os.environ, **(extra_env or {})}
    procs = [subprocess.Popen([sys.executable, "worker.py", *s], cwd=HERE, env=env, stdout=subprocess.PIPE, text=True)
             for s in specs]
    out = []
    for p in procs:
        o, _ = p.communicate()
        out += [line for line in o.splitlines() if line]
    return out


def exp1_ingress():
    print("== 1. 1000 条告警打进接入服务")
    alerts = make_alerts()
    tok = {"trade": "tk-trade", "pay": "tk-pay", "search": "tk-search"}
    lat, new, dup, merged = [], 0, 0, 0
    with httpx.Client(base_url=BASE) as cl:
        for i in range(0, len(alerts), 10):
            batch = alerts[i:i + 10]
            for t in ("trade", "pay", "search"):
                part = [a for a in batch if a["tenant"] == t]
                if not part:
                    continue
                t0 = time.perf_counter()
                res = cl.post("/alerts", json={"alerts": part}, headers={"Authorization": "Bearer " + tok[t]})
                lat.append((time.perf_counter() - t0) * 1000)
                assert res.status_code == 202, res.text
                j = res.json()
                new += len(j["new_incidents"]); dup += j["duplicates"]; merged += j["merged"]
    c = r()
    q = [c.xlen(s) for s in STREAMS]
    lat.sort()
    print(f"告警 {len(alerts)} 条 → 重发去掉 {dup}，合并 {merged}，新事件 {new}；队列 p0/p1/p2 = {q}")
    print(f"webhook 请求 {len(lat)} 个，202 耗时 p50 {statistics.median(lat):.1f} ms，p99 {lat[int(len(lat) * .99) - 1]:.1f} ms")


def exp2_priority():
    print("== 2. 4 个 worker 做完 87 个调查（每轮模型 0.15 秒）")
    t0 = time.time()
    workers([["--name", f"W{i}", "--idle-exit", "1.5"] for i in range(4)], {"MODEL_SEC": "0.15"})
    con = db()
    rows = con.execute("SELECT priority, at FROM done ORDER BY at").fetchall()
    order = [p for p, _ in rows]
    last_p0 = max(i for i, p in enumerate(order) if p == "P0") + 1
    first_p2 = min(i for i, p in enumerate(order) if p == "P2") + 1
    by = {p: [at - t0 for q, at in rows if q == p] for p in ("P0", "P1", "P2")}
    print(f"做完 {len(rows)} 个，用时 {rows[-1][1] - t0:.1f} 秒；最后一个 P0 是第 {last_p0} 个做完的，第一个 P2 是第 {first_p2} 个")
    print("各优先级平均完成时刻：" + "，".join(f"{p} {statistics.mean(v):.1f} 秒" for p, v in by.items()))
    calls = con.execute("SELECT COUNT(*) FROM model_calls").fetchone()[0]
    print(f"模型调用 {calls} 次（87 × 6 = 522）")


def exp3_crash():
    print("== 3. worker A 在第 5 轮（建工单之后、写检查点之前）被杀，B 接手")
    for ckpt in (True, False):
        for idem in (True, False):
            reset_all()
            c = r(); ensure_groups(c)
            c.xadd(STREAMS[0], {"incident": "trade-order-OrderApiP99High", "tenant": "trade", "priority": "P0"})
            flags = ([] if ckpt else ["--no-checkpoint"]) + ([] if idem else ["--no-idem"])
            t0 = time.time()
            out = workers([["--name", "A", "--crash-at", "5", "--max-tasks", "1", *flags]])
            out += workers([["--name", "B", "--min-idle-ms", "2000", "--idle-exit", "4", *flags]])
            con = db()
            calls = con.execute("SELECT COUNT(*) FROM model_calls").fetchone()[0]
            tickets = con.execute("SELECT COUNT(*) FROM tickets").fetchone()[0]
            done = con.execute("SELECT at FROM done").fetchone()
            pend = c.xpending(STREAMS[0], "investigators")["pending"]
            label = f"检查点{'有' if ckpt else '无'}、工具{'幂等' if idem else '不幂等'}"
            print(f"{label}：模型调用 {calls} 次，工单 {tickets} 张，做完用时 {done[0] - t0:.1f} 秒，待确认剩 {pend}")
            if ckpt and idem:
                for line in out:
                    print("   ", line)


def exp4_min_idle():
    print("== 4. 一轮模型要 1.5 秒，min-idle 只设 1 秒")
    for hb in (False, True):
        reset_all()
        c = r(); ensure_groups(c)
        c.xadd(STREAMS[1], {"incident": "pay-gateway-PayTimeout", "tenant": "pay", "priority": "P1"})
        flags = ["--min-idle-ms", "1000", "--idle-exit", "3"] + (["--heartbeat"] if hb else [])
        out = workers([["--name", "A", *flags], ["--name", "B", *flags]], {"MODEL_SEC": "1.5"})
        con = db()
        calls = con.execute("SELECT COUNT(*) FROM model_calls").fetchone()[0]
        who = con.execute("SELECT COUNT(DISTINCT worker) FROM model_calls").fetchone()[0]
        done = con.execute("SELECT COUNT(*) FROM done").fetchone()[0]
        tickets = con.execute("SELECT COUNT(*) FROM tickets").fetchone()[0]
        print(f"{'有心跳' if hb else '没心跳'}：模型调用 {calls} 次，经手 worker {who} 个，“做完”记了 {done} 次，工单 {tickets} 张")
        for line in out:
            print("   ", line)


def exp5_sse():
    print("== 5. SSE 读到第 3 条断开，带 Last-Event-ID 重连")
    reset_all()
    c = r(); ensure_groups(c)
    c.xadd(STREAMS[0], {"incident": "search-query-SearchSlow", "tenant": "search", "priority": "P0"})
    workers([["--name", "A", "--max-tasks", "1"]], {"MODEL_SEC": "0.1"})
    url = BASE + "/incidents/search-query-SearchSlow/events"
    got, last = [], "0-0"
    with httpx.stream("GET", url, timeout=5) as s:
        for line in s.iter_lines():
            if line.startswith("id: "):
                last = line[4:]
            if line.startswith("data: "):
                got.append(line[6:])
                if len(got) == 3:
                    break
    first = list(got)
    with httpx.stream("GET", url, headers={"Last-Event-ID": last}, timeout=5) as s:
        for line in s.iter_lines():
            if line.startswith("data: "):
                got.append(line[6:])
    print(f"第一次连接收到 {first}")
    print(f"重连后补上 {got[3:]}；一共 {len(got)} 条，重复 {len(got) - len(set(got))} 条")


if __name__ == "__main__":
    reset_all()
    ensure_groups(r())
    ing = start_ingress()
    try:
        exp1_ingress()
        exp2_priority()
        exp3_crash()
        exp4_min_idle()
        exp5_sse()
    finally:
        ing.terminate(); ing.wait()
        reset_all()
