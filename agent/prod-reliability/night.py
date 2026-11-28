"""把全景那一夜的 20:03 故障按 30 倍速重演 4 遍，比 4 种可靠性做法。

87 个事件按全景节奏到达，16 个 worker，每个调查 6 轮，每轮输入 5k、输出 400。
假供应商：20:03～20:06:20 主模型 70% 的请求回 529；全程 3% 的请求卡住 5 分钟才回。
自己这边：主模型一个 36 万/分钟的令牌桶，备用模型 13.5 万/分钟（供应商给 15 万）。

  A SDK 默认：超时 10 分钟、重试 2 次；不熔断、不降级。三次都失败的那一轮算失败，事件转人工
  B 三层重试：SDK 2 次 × 网关 3 次 × worker 重跑这一轮 3 次；超时 60 秒
  C 一层重试（网关 2 次，SDK 0 次）+ 超时 60 秒 + 熔断，打开时走备用模型
  D C + 每个事件一个截止时间（P0 3 分钟、P1 6 分钟、P2 15 分钟）+ 熔断打开时 P2 只分诊（备用模型一轮）

uv run python prod-reliability/night.py      # 约 3 分钟
"""
import asyncio
import os
import random
import subprocess
import sys
import time

import anthropic
import httpx

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from breaker import SCALE, CircuitBreaker, TokenBucket, now, sleep  # noqa: E402

PORT = 18730
BASE = f"http://127.0.0.1:{PORT}"
ROUND = "r" * (5000 * 4)
ARRIVALS = [27, 24, 17, 10, 6, 2, 1]
PRIO = ["P0"] * 9 + ["P1"] * 31 + ["P2"] * 47
DEADLINE = {"P0": 180, "P1": 360, "P2": 900}


class Failed(Exception):
    pass


def provider_down(e: Exception) -> bool:
    """供应商那边坏了：529、5xx、超时、连不上。429 和 4xx 不算。"""
    if isinstance(e, anthropic.APIConnectionError):          # 包括 APITimeoutError
        return True
    return isinstance(e, anthropic.APIStatusError) and e.status_code >= 500


async def run(v: str) -> dict:
    httpx.post(BASE + "/reset")
    rng = random.Random(7)
    timeout = (600 if v == "A" else 60) / SCALE                # SDK 的超时是墙上时间
    sdk_retries = {"A": 2, "B": 2}.get(v, 0)
    client = anthropic.AsyncAnthropic(base_url=BASE, api_key="sk-fake", max_retries=sdk_retries, timeout=timeout)
    buckets = {"main": TokenBucket(360_000), "backup": TokenBucket(135_000)}
    br = CircuitBreaker() if v in "CD" else None
    q: asyncio.PriorityQueue = asyncio.PriorityQueue()
    t_start = now()
    res = {"done": [], "failed": [], "degraded": 0, "backup_calls": 0}

    async def one_call(model: str) -> None:
        await buckets[model].acquire(5000)
        await client.messages.create(model=model, max_tokens=1024, messages=[{"role": "user", "content": ROUND}])

    async def gateway_call(inc) -> None:
        layer = {"A": 1, "B": 3}.get(v, 3)                       # 网关这一层最多试几次
        for attempt in range(layer):
            if v in "AB":
                try:
                    return await one_call("main")
                except anthropic.APIError as e:
                    if attempt == layer - 1:
                        raise Failed from e
                    await sleep(min(8, 0.5 * 2 ** attempt) * (0.5 + rng.random()))
                continue
            # C、D：先问熔断器，主模型不让用就走备用
            if v == "D" and now() > inc["deadline"]:
                raise Failed("deadline")
            model = "main" if br.allow() else "backup"
            try:
                await one_call(model)
                if model == "main":
                    br.record(True)
                else:
                    res["backup_calls"] += 1
                return
            except anthropic.APIError as e:
                if model == "main" and provider_down(e):
                    br.record(False)
                if attempt == layer - 1:
                    raise Failed from e
                if isinstance(e, anthropic.RateLimitError):      # 429：照 retry-after 等，不算熔断
                    await asyncio.sleep(float(e.response.headers.get("retry-after-ms", "1000")) / 1000)
                else:
                    await sleep(min(8, 0.5 * 2 ** attempt) * (0.5 + rng.random()))

    async def investigate(inc) -> None:
        rounds = 6
        if v == "D" and inc["prio"] == "P2" and br.state != "CLOSED":
            rounds = 1; res["degraded"] += 1                     # 只分诊：一轮，备用模型
        for _ in range(rounds):
            tries = 3 if v == "B" else 1                         # B：worker 再包一层，重跑这一轮
            for t in range(tries):
                try:
                    await gateway_call(inc); break
                except Failed:
                    if t == tries - 1:
                        raise
            await sleep(2)

    async def worker():
        while True:
            _, _, inc = await q.get()
            try:
                await investigate(inc); inc["end"] = now(); res["done"].append(inc)
            except Failed:
                inc["end"] = now(); res["failed"].append(inc)
            finally:
                q.task_done()

    async def feeder():
        k = 0
        for n in ARRIVALS:
            for _ in range(n):
                inc = {"id": k, "prio": PRIO[(k * 37) % 87], "arrive": now()}
                inc["deadline"] = inc["arrive"] + DEADLINE[inc["prio"]]
                q.put_nowait(({"P0": 0, "P1": 1, "P2": 2}[inc["prio"]], k, inc)); k += 1
                await sleep(60 / n)

    ws = [asyncio.create_task(worker()) for _ in range(16)]
    await feeder(); await q.join()
    for w in ws:
        w.cancel()
    st = httpx.get(BASE + "/stats").json()
    m, b = st.get("main", {}), st.get("backup", {})
    done = res["done"]
    lat = sorted((i["end"] - i["arrive"]) / 60 for i in done)
    p0 = [(i["end"] - i["arrive"]) / 60 for i in done if i["prio"] == "P0"]      # P0 从进来到出报告最长多久
    return {"v": v, "main_outage_req": m.get("outage_req", 0), "main_529": m.get("529", 0), "hang": m.get("hang", 0),
            "failed": len(res["failed"]), "failed_p0": sum(i["prio"] == "P0" for i in res["failed"]),
            "end": (max(i["end"] for i in done + res["failed"]) - t_start) / 60,
            "p0": max(p0) if p0 else float("nan"), "p95": lat[int(len(lat) * .95) - 1] if lat else float("nan"),
            "backup": b.get("ok", 0), "degraded": res["degraded"],
            "breaker": [(round((t - t_start) / 60, 2), s) for t, s in br.log] if br else []}


def main():
    p = subprocess.Popen([sys.executable, "-m", "uvicorn", "fake_provider:app", "--port", str(PORT), "--log-level", "warning"],
                         cwd=HERE, env=os.environ.copy())
    try:
        for _ in range(50):
            try:
                httpx.get(BASE + "/stats", timeout=0.5); break
            except httpx.HTTPError:
                time.sleep(0.2)
        names = {"A": "SDK 默认", "B": "三层重试", "C": "一层重试+熔断+备用", "D": "C+截止时间+P2只分诊"}
        print(f"{'做法':<22}{'故障期打主模型':>8}{'529':>6}{'卡住':>5}{'转人工':>6}{'其中P0':>6}{'全部做完':>9}{'P0最长':>8}{'P95':>7}{'备用':>6}{'只分诊':>6}")
        for v in "ABCD":
            r = asyncio.run(run(v))
            print(f"{v} {names[v]:<20}{r['main_outage_req']:>10}{r['main_529']:>6}{r['hang']:>5}{r['failed']:>6}{r['failed_p0']:>6}"
                  f"{r['end']:>8.1f}分{r['p0']:>7.1f}分{r['p95']:>6.1f}分{r['backup']:>6}{r['degraded']:>6}", flush=True)
            if r["breaker"]:
                print("   熔断器：" + " → ".join(f"{s}@20:{int(t):02d}:{int(t % 1 * 60):02d}" for t, s in r["breaker"]))
    finally:
        p.terminate(); p.wait()


if __name__ == "__main__":
    main()
