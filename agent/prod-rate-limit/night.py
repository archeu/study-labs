"""把全景那一夜按 30 倍速重演 4 遍，比较 4 种限流做法。

87 个事件按全景的节奏到达（每分钟 27、24、17、10、6、2、1 个），16 个 worker，每个调查 6 轮：
每轮输入 9k token，其中 4k 是带 cache_control 的固定前缀；搜索团队的事件第 3 轮多带一段 4 万 token 的日志。
供应商额度（假服务）：RPM 1000、ITPM 40 万（只算没命中缓存的）、OTPM 8 万，按 10 秒一段卡。
调用用官方 anthropic SDK（max_retries=2，照 retry-after 等）。重试用完还是 429 的那一轮算失败，事件转人工。

  A 只靠供应商的 429 和 SDK 重试
  B 自己限请求数：每分钟 72 个（= 36 万 ÷ 5k），不看大小
  C 自己按 token 限，但按 5 : 3 : 2 死分成三个租户桶（合计 36 万/分钟），闲着的份额借不走
  E 自己按 token 限：一个 36 万/分钟的全局桶 + 加权公平排队（5 : 3 : 2 是保底），P0 先拿，预扣 + 多退少补
  D 同 E，但有人在系统提示里加了当前时间，前缀缓存一次也不中

uv run python prod-rate-limit/night.py      # 约 2 分钟
"""
import asyncio
import os
import statistics
import subprocess
import sys
import time

import anthropic
import httpx

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from bucket import FairBucket, TenantBuckets, TokenBucket, now, sleep  # noqa: E402

PORT = 18720
BASE = f"http://127.0.0.1:{PORT}"
PREFIX = "你是值班助手。排查步骤……" + "p" * (4000 * 4 - 12)     # 4k token 的系统提示 + 工具说明
ROUND = "r" * (5000 * 4)                                         # 每轮 5k token 的历史和工具结果
BIGLOG = "L" * (40000 * 4)                                       # 搜索团队第 3 轮那段 4 万 token 的日志
ARRIVALS = [27, 24, 17, 10, 6, 2, 1]


def incidents():
    tenants = ["trade"] * 52 + ["pay"] * 23 + ["search"] * 12
    prio = (["P0"] * 9 + ["P1"] * 31 + ["P2"] * 47)
    # 让 P0 都在交易和支付，P2 多在搜索：和全景一致
    order = sorted(range(87), key=lambda i: (tenants[i] == "search", i % 7))
    out = [{"id": f"inc{i:02d}", "tenant": tenants[i], "prio": prio[k]} for k, i in enumerate(order)]
    # 按优先级打散到 7 分钟里
    out.sort(key=lambda x: (x["id"]))
    return out


async def run(variant: str) -> dict:
    httpx.post(BASE + "/reset")
    client = anthropic.AsyncAnthropic(base_url=BASE, api_key="sk-fake", max_retries=2)
    tb = TenantBuckets(360_000, {"trade": 5, "pay": 3, "search": 2}) if variant == "C" else None
    fb = FairBucket(360_000, {"trade": 5, "pay": 3, "search": 2}) if variant in "ED" else None
    rb = TokenBucket(72) if variant == "B" else None       # 每分钟 72 个请求，同样按 10 秒一段
    q: asyncio.PriorityQueue = asyncio.PriorityQueue()
    incs = incidents()
    t_start = now()
    done, failed = [], []

    async def feeder():
        k = 0
        for minute, n in enumerate(ARRIVALS):
            for j in range(n):
                inc = incs[k]; k += 1
                inc["arrive"] = now()
                q.put_nowait(({"P0": 0, "P1": 1, "P2": 2}[inc["prio"]], inc["arrive"], inc["id"], inc))
                await sleep(60 / n)

    async def call(inc, rnd):
        system = [{"type": "text", "text": PREFIX, "cache_control": {"type": "ephemeral"}}]
        if variant == "D":
            system = [{"type": "text", "text": f"当前时间 {now():.3f}\n" + PREFIX, "cache_control": {"type": "ephemeral"}}]
        content = ROUND + (BIGLOG if inc["tenant"] == "search" and rnd == 3 else "")
        est = len(content) / 4                       # 预估：假定前缀命中缓存，只算这一轮新增的
        if tb:
            await tb[inc["tenant"]].acquire(est)
        if fb:
            await fb.acquire(inc["tenant"], est, {"P0": 0, "P1": 1, "P2": 2}[inc["prio"]])
        if rb:
            await rb.acquire(1)
        resp = await client.messages.create(model="main", max_tokens=1024, system=system,
                                            messages=[{"role": "user", "content": content}],
                                            extra_headers={"x-tenant": inc["tenant"]})
        u = resp.usage
        if tb:
            tb[inc["tenant"]].settle(est, u.input_tokens + u.cache_creation_input_tokens)
        if fb:
            fb.settle(inc["tenant"], est, u.input_tokens + u.cache_creation_input_tokens)

    async def worker():
        while True:
            _, _, _, inc = await q.get()
            inc["start"] = now()
            try:
                for rnd in range(1, 7):
                    await call(inc, rnd)
                    await sleep(2)                    # 工具 2 秒
                inc["end"] = now(); done.append(inc)
            except anthropic.RateLimitError:
                inc["end"] = now(); failed.append(inc)
            finally:
                q.task_done()

    ws = [asyncio.create_task(worker()) for _ in range(16)]
    await feeder()
    await q.join()
    for w in ws:
        w.cancel()
    if fb:
        fb.task.cancel()
    st = httpx.get(BASE + "/stats").json()
    lat = sorted((i["end"] - i["arrive"]) / 60 for i in done)
    p0 = [(i["end"] - t_start) / 60 for i in done if i["prio"] == "P0"]
    pt = st["per_tenant"]
    return {"variant": variant, "requests": st["requests"], "429": st["429"], "failed": len(failed),
            "failed_p0": sum(1 for i in failed if i["prio"] == "P0"),
            "end_min": (max(i["end"] for i in done + failed) - t_start) / 60,
            "p0_last_min": max(p0) if p0 else float("nan"),
            "p95_min": lat[int(len(lat) * 0.95) - 1] if lat else float("nan"),
            "cache_read": st["cache_read"], "input": st["input"] + st["cache_write"],
            "trade429": pt.get("trade", {}).get("429", 0), "search429": pt.get("search", {}).get("429", 0)}


def main():
    env = {**os.environ}
    p = subprocess.Popen([sys.executable, "-m", "uvicorn", "fake_provider:app", "--port", str(PORT), "--log-level", "warning"],
                         cwd=HERE, env=env)
    try:
        for _ in range(50):
            try:
                httpx.get(BASE + "/stats", timeout=0.5); break
            except httpx.HTTPError:
                time.sleep(0.2)
        names = {"A": "只靠 429 + SDK 重试", "B": "自己限请求数", "C": "租户桶死分", "E": "全局桶+公平排队", "D": "E + 缓存坏了"}
        print(f"{'做法':<16}{'请求':>6}{'429':>6}{'失败事件':>8}{'其中P0':>6}{'全部做完':>9}{'P0做完':>8}{'P95':>7}"
              f"{'交易429':>8}{'搜索429':>8}{'计入ITPM':>10}{'缓存读':>9}")
        for v in "ABCED":
            r = asyncio.run(run(v))
            print(f"{v} {names[v]:<14}{r['requests']:>6}{r['429']:>6}{r['failed']:>8}{r['failed_p0']:>6}"
                  f"{r['end_min']:>8.1f}分{r['p0_last_min']:>7.1f}分{r['p95_min']:>6.1f}分"
                  f"{r['trade429']:>8}{r['search429']:>8}{r['input']/1e6:>9.2f}M{r['cache_read']/1e6:>8.2f}M", flush=True)
    finally:
        p.terminate(); p.wait()


if __name__ == "__main__":
    main()
