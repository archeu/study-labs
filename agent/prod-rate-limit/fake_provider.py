"""假供应商：只实现 POST /v1/messages，按 RPM / ITPM / OTPM 三个令牌桶限速，并模拟前缀缓存。

规则照 Claude API 文档的 rate-limits 页（2026-11-28 查）：
- 三个桶都是连续补充的令牌桶；可能按比一分钟更短的间隔执行，这里按“10 秒一段”卡（桶容量 = 10 秒的量）。
- ITPM 只算没命中缓存的输入：input_tokens + cache_creation_input_tokens；cache_read_input_tokens 不算。
- OTPM 只算真正生成的输出，和 max_tokens 无关。
- 超了回 429 rate_limit_error，带 retry-after（秒）和 anthropic-ratelimit-input-tokens-remaining。
时间压缩：SCALE=30 时，场景里的 1 分钟在墙上只过 2 秒；额度、耗时都按场景时间写。

uvicorn fake_provider:app --port 18720
"""
import asyncio
import hashlib
import math
import os
import time

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

SCALE = float(os.environ.get("SCALE", "30"))
RPM = float(os.environ.get("RPM", "1000"))
ITPM = float(os.environ.get("ITPM", "400000"))
OTPM = float(os.environ.get("OTPM", "80000"))
WINDOW = 10.0                 # 桶容量 = 10 场景秒的量
OUT_TOKENS = 400              # 每次固定生成 400 个 token
GEN_TPS = 50.0                # 生成速度：每场景秒 50 个 token（400 个输出 ≈ 8 秒）
CACHE_TTL = 300.0             # 前缀缓存 5 分钟
CHARS_PER_TOKEN = 4

t0 = time.monotonic()


def now() -> float:            # 场景时间（秒）
    return (time.monotonic() - t0) * SCALE


class Bucket:
    def __init__(self, per_min: float):
        self.rate = per_min / 60.0
        self.cap = self.rate * WINDOW
        self.tokens, self.t = self.cap, now()

    def refill(self) -> None:
        t = now()
        self.tokens = min(self.cap, self.tokens + (t - self.t) * self.rate)
        self.t = t

    def take(self, n: float) -> float:
        """够就扣掉返回 0；不够返回要等的场景秒数。"""
        self.refill()
        if self.tokens >= n:
            self.tokens -= n
            return 0.0
        return (n - self.tokens) / self.rate


rpm, itpm, otpm = Bucket(RPM), Bucket(ITPM), Bucket(OTPM)
cache: dict[str, float] = {}
stats = {"requests": 0, "ok": 0, "429": 0, "429_itpm": 0, "429_rpm": 0, "429_otpm": 0,
         "input": 0, "cache_read": 0, "cache_write": 0, "output": 0}
per_tenant: dict[str, dict] = {}   # 请求头 x-tenant → 这个租户吃了几次 429
app = FastAPI()


def tok(s: str) -> int:
    return math.ceil(len(s) / CHARS_PER_TOKEN)


def too_many(which: str, wait: float, remaining: float, tenant: str) -> JSONResponse:
    stats["429"] += 1
    per_tenant.setdefault(tenant, {"ok": 0, "429": 0})["429"] += 1
    stats["429_" + which] += 1
    return JSONResponse(status_code=429, content={"type": "error", "error": {
        "type": "rate_limit_error", "message": f"This request would exceed the rate limit for {which.upper()}"}},
        headers={"retry-after": str(max(1, math.ceil(wait / SCALE))),    # 墙上时间的秒数
                 "retry-after-ms": str(int(wait / SCALE * 1000) + 1),          # SDK 优先读这个，压缩时间下更准
                 "anthropic-ratelimit-input-tokens-remaining": str(int(max(0, remaining)))})


@app.post("/v1/messages")
async def messages(req: Request):
    body = await req.json()
    stats["requests"] += 1
    tenant = req.headers.get("x-tenant", "?")
    sys_blocks = body.get("system") or []
    prefix = "".join(b["text"] for b in sys_blocks if b.get("cache_control"))
    rest = "".join(b["text"] for b in sys_blocks if not b.get("cache_control"))
    rest += "".join(m["content"] if isinstance(m["content"], str) else "".join(c.get("text", "") for c in m["content"])
                    for m in body["messages"])
    key = hashlib.sha256(prefix.encode()).hexdigest() if prefix else None
    hit = key is not None and cache.get(key, -1) > now()
    cache_read = tok(prefix) if hit else 0
    cache_write = tok(prefix) if (prefix and not hit) else 0
    uncached = tok(rest)
    # 三个桶依次检查；ITPM 只收没命中缓存的部分
    w = rpm.take(1)
    if w:
        return too_many("rpm", w, itpm.tokens, tenant)
    w = itpm.take(uncached + cache_write)
    if w:
        rpm.tokens += 1
        return too_many("itpm", w, itpm.tokens, tenant)
    otpm.refill()
    if otpm.tokens <= 0:
        return too_many("otpm", -otpm.tokens / otpm.rate + 1, itpm.tokens, tenant)
    out = min(OUT_TOKENS, body.get("max_tokens", 1024))
    await asyncio.sleep(out / GEN_TPS / SCALE)          # 生成要时间
    otpm.tokens -= out                                   # 只算真正生成的
    if key:
        cache[key] = now() + CACHE_TTL
    stats["ok"] += 1
    per_tenant.setdefault(tenant, {"ok": 0, "429": 0})["ok"] += 1
    stats["input"] += uncached; stats["cache_read"] += cache_read; stats["cache_write"] += cache_write; stats["output"] += out
    return {"id": "msg_fake", "type": "message", "role": "assistant", "model": body["model"],
            "content": [{"type": "text", "text": "x" * (out * CHARS_PER_TOKEN)}],
            "stop_reason": "end_turn", "stop_sequence": None,
            "usage": {"input_tokens": uncached, "output_tokens": out,
                      "cache_read_input_tokens": cache_read, "cache_creation_input_tokens": cache_write}}


@app.get("/stats")
async def get_stats():
    return {**stats, "per_tenant": per_tenant}


@app.post("/reset")
async def reset():
    global rpm, itpm, otpm, t0
    for k in stats:
        stats[k] = 0
    cache.clear()
    per_tenant.clear()
    t0 = time.monotonic()
    rpm, itpm, otpm = Bucket(RPM), Bucket(ITPM), Bucket(OTPM)
    return stats
