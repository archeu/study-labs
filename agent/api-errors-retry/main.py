"""出错怎么办：30 个客户端同时撞上限流（每秒放行 5 个），5 种重试策略比一比；再看官方 SDK 自带的重试。

    python main.py herd      # 5 种策略：429 次数、全部成功用了多久
    python main.py sdk       # 官方 SDK：遇到 529 时自动重试了几次、隔了多久；超时又是什么样
需要先起 fake_server.py（127.0.0.1:8097）。只用标准库 + anthropic。
"""

import json
import random
import sys
import threading
import time
import urllib.error
import urllib.request

import anthropic

URL = "http://127.0.0.1:8097"
N_CLIENTS, MAX_TRIES, BASE, CAP = 30, 20, 0.2, 8.0


def call() -> tuple[int, str | None]:
    req = urllib.request.Request(URL + "/v1/messages", json.dumps({"model": "fake-ok"}).encode(),
                                 {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, None
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("retry-after")


def wait_time(strategy: str, attempt: int, retry_after: str | None) -> float:
    exp = min(CAP, BASE * 2 ** attempt)
    return {
        "立即重试": 0.0,
        "固定 1 秒": 1.0,
        "指数退避": exp,
        "指数退避+全抖动": random.uniform(0, exp),
        "按 retry-after+抖动": float(retry_after or 1) + random.uniform(0, 1.0),
    }[strategy]


def client(strategy: str, out: list) -> None:
    t0 = time.time()
    for attempt in range(MAX_TRIES):
        code, ra = call()
        if code == 200:
            out.append(time.time() - t0)
            return
        time.sleep(wait_time(strategy, attempt, ra))
    out.append(None)


def stats(reset: bool = False) -> dict:
    with urllib.request.urlopen(URL + "/stats" + ("?reset=1" if reset else "")) as r:
        return json.load(r)


def cmd_herd() -> None:
    random.seed(7)
    for strategy in ("立即重试", "固定 1 秒", "指数退避", "指数退避+全抖动", "按 retry-after+抖动"):
        stats(reset=True)
        time.sleep(1.2)                 # 等令牌桶回满，每种策略起点一样
        stats(reset=True)
        done: list = []
        ths = [threading.Thread(target=client, args=(strategy, done)) for _ in range(N_CLIENTS)]
        for t in ths:
            t.start()
        for t in ths:
            t.join()
        s = stats()
        ok = [d for d in done if d is not None]
        # 到达最集中的 100 毫秒里有几个请求
        arr = s["arrivals"]
        peak = max(sum(1 for b in arr if a <= b < a + 0.1) for a in arr) if arr else 0
        print(f"{strategy:<12} 成功 {len(ok):>2}/{N_CLIENTS}  请求共 {len(arr):>4} 次（429 {s['429']:>4} 次）  "
              f"全部成功用时 {max(ok):5.1f} s  最挤的 100ms 里 {peak:>2} 个请求")


def cmd_sdk() -> None:
    stats(reset=True)
    time.sleep(1.2)
    stamps = []

    def hook(req):
        stamps.append(time.time())

    hc = anthropic.DefaultHttpxClient(event_hooks={"request": [hook]})
    c = anthropic.Anthropic(base_url=URL, api_key="x", http_client=hc, max_retries=2)
    t0 = time.time()
    m = c.messages.create(model="fake-529-2", max_tokens=5, metadata={"user_id": "u1"},
                          messages=[{"role": "user", "content": "hi"}])
    gaps = [f"{b - a:.2f}s" for a, b in zip(stamps, stamps[1:])]
    print(f"529 两次后成功：SDK 一共发了 {len(stamps)} 次请求，间隔 {gaps}，总用时 {time.time() - t0:.2f}s，结果 {m.content[0].text}")

    stamps.clear()
    c2 = anthropic.Anthropic(base_url=URL, api_key="x", http_client=hc, max_retries=2, timeout=1.0)
    t0 = time.time()
    try:
        c2.messages.create(model="fake-slow", max_tokens=5, messages=[{"role": "user", "content": "hi"}])
    except anthropic.APITimeoutError as e:
        gaps = [f"{b - a:.2f}s" for a, b in zip(stamps, stamps[1:])]
        print(f"超时 1s、服务要 3s：SDK 发了 {len(stamps)} 次，间隔 {gaps}，{time.time() - t0:.2f}s 后抛 {type(e).__name__}")


if __name__ == "__main__":
    {"herd": cmd_herd, "sdk": cmd_sdk}[sys.argv[1] if len(sys.argv) > 1 else "herd"]()
