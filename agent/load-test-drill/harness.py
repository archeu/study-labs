"""压测和故障演练：把前几页的部件拼成一个小值班助手，按“开环”的到达节奏打进去，记下这一夜的指标。

开环：事件按时间表到达，不管系统处理得过来处理不过来（真实的告警就是这样）；
闭环（N 个虚拟用户各等自己的上一个做完再发下一个）会把排队藏起来，压测结果偏乐观。

组成：TokenBucket（主模型 36 万、备用 13.5 万 ITPM）+ CircuitBreaker + 备用模型 + 每个事件的截止时间
     + 只在网关一层重试 + 工具层（可以注入“工具挂掉”）。调用走官方 anthropic SDK → 假供应商。
"""
import asyncio
import random
import statistics

import anthropic
import httpx

from breaker import SCALE, CircuitBreaker, TokenBucket, now, sleep

ARRIVALS = [27, 24, 17, 10, 6, 2, 1]
PRIO = ["P0"] * 9 + ["P1"] * 31 + ["P2"] * 47
DEADLINE = {"P0": 180, "P1": 360, "P2": 900}
ROUND = "r" * (5000 * 4)
PRICE_IN, PRICE_OUT = 3.0, 15.0


class Escalate(Exception):
    pass


async def run_night(base: str, mult: int = 1, workers: int = 16, events: list | None = None, tool_down=None, seed=7) -> dict:
    """mult：告警量是全景那一夜的几倍。events：[(场景秒, {control 参数})]，到点改假供应商的故障。
    tool_down：(开始秒, 结束秒)，这段时间 search_logs 一律报错。"""
    httpx.post(base + "/reset")
    rng = random.Random(seed)
    client = anthropic.AsyncAnthropic(base_url=base, api_key="sk-fake", max_retries=0, timeout=60 / SCALE)
    buckets = {"main": TokenBucket(360_000), "backup": TokenBucket(135_000)}
    br = CircuitBreaker()
    q: asyncio.PriorityQueue = asyncio.PriorityQueue()
    t0 = now()
    m = {"done": [], "escalated": [], "missing_evidence": 0, "r429": 0, "r529": 0, "timeouts": 0, "backup": 0,
         "tokens": 0, "out": 0, "lag": [], "tool_errors": 0}

    async def call() -> None:
        for attempt in range(3):
            model = "main" if br.allow() else "backup"
            await buckets[model].acquire(5000)
            try:
                r = await client.messages.create(model=model, max_tokens=512, messages=[{"role": "user", "content": ROUND}])
                if model == "main":
                    br.record(True)
                else:
                    m["backup"] += 1
                m["tokens"] += r.usage.input_tokens; m["out"] += r.usage.output_tokens
                return
            except anthropic.RateLimitError as e:
                m["r429"] += 1
                await asyncio.sleep(float(e.response.headers.get("retry-after-ms", "1000")) / 1000)
            except (anthropic.APIConnectionError, anthropic.InternalServerError) as e:
                if isinstance(e, anthropic.APITimeoutError):
                    m["timeouts"] += 1
                else:
                    m["r529"] += 1
                if model == "main":
                    br.record(False)
                await sleep(min(8, 0.5 * 2 ** attempt) * (0.5 + rng.random()))
        raise Escalate("model")

    async def tool(name: str) -> bool:
        await sleep(2)
        if tool_down and name == "search_logs" and tool_down[0] <= now() - t0 < tool_down[1]:
            m["tool_errors"] += 1
            return False          # 工具挂了：记一笔“证据缺失”，继续用别的工具，而不是让整个调查失败
        return True

    async def investigate(inc) -> None:
        missing = False
        for name in ("search_logs", "parse_gc_log", "thread_dump", "search_notes", "create_ticket", None):
            if now() > inc["deadline"]:
                raise Escalate("deadline")
            await call()
            if name and not await tool(name):
                missing = True
        if missing:
            m["missing_evidence"] += 1

    async def worker():
        while True:
            _, _, inc = await q.get()
            try:
                await investigate(inc); inc["end"] = now(); m["done"].append(inc)
            except Escalate:
                inc["end"] = now(); m["escalated"].append(inc)
            finally:
                q.task_done()

    async def feeder():
        k = 0
        for n in ARRIVALS:
            n *= mult
            for _ in range(n):
                p = PRIO[(k * 37) % 87]
                inc = {"id": k, "prio": p, "arrive": now()}
                inc["deadline"] = inc["arrive"] + DEADLINE[p]
                q.put_nowait(({"P0": 0, "P1": 1, "P2": 2}[p], k, inc)); k += 1
                await sleep(60 / n)

    async def controller():
        for at, ctl in sorted(events or [], key=lambda x: x[0]):
            await sleep(at - (now() - t0))
            httpx.post(base + "/control", json=ctl)

    async def sampler():
        while True:
            m["lag"].append(q.qsize())
            await sleep(60)

    ws = [asyncio.create_task(worker()) for _ in range(workers)]
    side = [asyncio.create_task(controller()), asyncio.create_task(sampler())]
    await feeder(); await q.join()
    for t in ws + side:
        t.cancel()
    all_ = m["done"] + m["escalated"]
    lat = sorted((i["end"] - i["arrive"]) / 60 for i in m["done"])
    p0 = [(i["end"] - i["arrive"]) / 60 for i in m["done"] if i["prio"] == "P0"]
    return {"incidents": len(all_), "done": len(m["done"]), "escalated": len(m["escalated"]),
            "escalated_p0": sum(i["prio"] == "P0" for i in m["escalated"]),
            "end_min": (max(i["end"] for i in all_) - t0) / 60, "p50": statistics.median(lat), "p95": lat[int(len(lat) * .95) - 1],
            "p0_max": max(p0) if p0 else float("nan"), "lag_max": max(m["lag"]), "r429": m["r429"], "r529": m["r529"],
            "timeouts": m["timeouts"], "backup": m["backup"], "missing": m["missing_evidence"], "tool_errors": m["tool_errors"],
            "usd": (m["tokens"] * PRICE_IN + m["out"] * PRICE_OUT) / 1e6,
            "breaker": [(round((t - t0) / 60, 2), s) for t, s in br.log]}
