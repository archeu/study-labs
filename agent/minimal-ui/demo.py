"""用 httpx 当浏览器，把值班网页的几条关键路径走一遍。

uv run python minimal-ui/demo.py
"""
import os
import shutil
import subprocess
import sys
import time

import httpx

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = "http://127.0.0.1:18760"
INC = "pay-gateway-PayCallbackSlow"
DATA = os.path.join(HERE, "data")


def sse(resp, limit=None):
    """把 SSE 响应拆成 (id, event, data)；读到 limit 条就停（模拟断线）。"""
    cur, n = {}, 0
    for line in resp.iter_lines():
        if not line:
            if cur:
                yield cur["id"], cur["event"], cur["data"]; n += 1; cur = {}
                if limit and n >= limit:
                    return
            continue
        k, _, v = line.partition(": ")
        cur[k] = int(v) if k == "id" else v


def main():
    shutil.rmtree(DATA, ignore_errors=True)
    p = subprocess.Popen([sys.executable, "-m", "uvicorn", "app:app", "--port", "18760", "--log-level", "warning"],
                         cwd=HERE, env={**os.environ, "TOKEN_SEC": "0.02"}, stderr=subprocess.DEVNULL)
    try:
        for _ in range(50):
            try:
                httpx.get(BASE + "/api/incidents", timeout=0.5); break
            except httpx.HTTPError:
                time.sleep(0.2)
        page = httpx.get(BASE + "/").text
        print("== 0 页面：模型输出用 textContent 写进去（不是 innerHTML）："
              f"{'textContent' in page and 'innerHTML' not in page}")

        print("== 1 开始调查：双击、刷新都不会再起一次")
        h = {"X-User": "zhang"}
        for label, key in (("第一次点", "k-1"), ("双击（同一个键）", "k-1"), ("刷新后丢了键（新键）", "k-2")):
            r = httpx.post(f"{BASE}/api/incidents/{INC}/start", headers={**h, "Idempotency-Key": key}).json()
            print(f"   {label:<14} → started={r['started']}")

        print("== 2 流式：第一段报告多久到，整篇多久完")
        t0 = time.perf_counter(); first = None; first_prog = None; got = []
        with httpx.stream("GET", f"{BASE}/api/incidents/{INC}/stream", timeout=30) as s:
            for i, (eid, ev, data) in enumerate(sse(s)):
                if ev == "report" and first is None:
                    first = time.perf_counter() - t0
                if ev == "progress" and first_prog is None:
                    first_prog = time.perf_counter() - t0
                got.append((eid, ev, data))
        total = time.perf_counter() - t0
        n_report = sum(1 for _, ev, _ in got if ev == "report")
        n_prog = sum(1 for _, ev, _ in got if ev == "progress")
        print(f"   进度 {n_prog} 条 + 报告 {n_report} 段；第一条进度 {first_prog:.2f} 秒，第一段报告 {first:.2f} 秒，整篇 {total:.2f} 秒（不流式就要等 {total:.2f} 秒才看到第一个字）")

        print("== 3 断线重连：读到第 10 条断开，带 Last-Event-ID 接着读")
        with httpx.stream("GET", f"{BASE}/api/incidents/{INC}/stream", timeout=30) as s:
            part1 = list(sse(s, limit=10))
        with httpx.stream("GET", f"{BASE}/api/incidents/{INC}/stream", headers={"Last-Event-ID": str(part1[-1][0])}, timeout=30) as s:
            part2 = list(sse(s))
        ids = [e[0] for e in part1 + part2]
        whole = "".join(d for _, ev, d in part1 + part2 if ev == "report")
        print(f"   第一次 {len(part1)} 条，重连补 {len(part2)} 条，id 连续不重复：{ids == list(range(1, len(ids) + 1))}，拼出的报告和一次读完的一样：{whole == ''.join(d for _, ev, d in got if ev == 'report')}")
        print(f"   报告里那段合作方备注原样是文字：{'<img src=x onerror=alert(1)>' in whole}")

        print("== 4 反馈：同一个人先 👍 再 👎，只算最后一次；👎 写了理由的进评测集候选")
        httpx.post(f"{BASE}/api/incidents/{INC}/feedback", headers=h, json={"vote": "up"})
        r = httpx.post(f"{BASE}/api/incidents/{INC}/feedback", headers=h,
                       json={"vote": "down", "reason": "建议把连接池调到 40 不对：数据库只能再扛 10 个连接"}).json()
        r2 = httpx.post(f"{BASE}/api/incidents/{INC}/feedback", headers={"X-User": "li"}, json={"vote": "down"}).json()
        cands = httpx.get(BASE + "/api/eval-candidates").json()
        print(f"   zhang 投了两次，这个事件记 {r['votes_for_incident']} 票；li 再投（没写理由），记 {r2['votes_for_incident']} 票")
        print(f"   评测集候选 {len(cands)} 条：{cands[0]['why']}")

        print("== 5 转人工：呼值班的人，带上已有的证据")
        r = httpx.post(f"{BASE}/api/incidents/{INC}/escalate", headers=h).json()
        print(f"   呼叫 {r['paged']}，附 {r['evidence_rounds']} 轮证据")
    finally:
        p.terminate(); p.wait()
        shutil.rmtree(DATA, ignore_errors=True)


if __name__ == "__main__":
    main()
