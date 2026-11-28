"""会出故障的假供应商：两个模型 main（主）和 backup（备用），各有自己的 ITPM 令牌桶。

故障按场景时间（秒，从 20:00 起）注入：
- OUTAGE_START～OUTAGE_END（默认 180～380，即 20:03～20:06:20）：main 的请求有 FAIL_RATE（默认 0.7）回 529 overloaded_error；
- 全程：main 有 HANG_RATE（默认 0.03）的请求卡住 HANG_SEC（默认 300）秒才回，模拟连接半死不活。
backup 不出故障，但额度小（BACKUP_ITPM，默认 15 万），生成也慢一点。
时间压缩：SCALE=30，场景 1 分钟 = 墙上 2 秒。GET /stats 给每个模型分时段的请求数。

uvicorn fake_provider:app --port 18730
"""
import asyncio
import math
import os
import random
import time

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

SCALE = float(os.environ.get("SCALE", "30"))
OUTAGE = (float(os.environ.get("OUTAGE_START", "180")), float(os.environ.get("OUTAGE_END", "380")))
FAIL_RATE = float(os.environ.get("FAIL_RATE", "0.7"))
HANG_RATE = float(os.environ.get("HANG_RATE", "0.03"))
HANG_SEC = float(os.environ.get("HANG_SEC", "300"))
ITPM = {"main": float(os.environ.get("MAIN_ITPM", "400000")), "backup": float(os.environ.get("BACKUP_ITPM", "150000"))}
GEN_TPS = {"main": 50.0, "backup": 40.0}
OUT = 400
WINDOW = 10.0

t0 = time.monotonic()
rnd = random.Random(int(os.environ.get("SEED", "1")))


def now() -> float:
    return (time.monotonic() - t0) * SCALE


class Bucket:
    def __init__(self, per_min):
        self.rate = per_min / 60.0; self.cap = self.rate * WINDOW; self.tokens = self.cap; self.t = now()

    def take(self, n):
        t = now(); self.tokens = min(self.cap, self.tokens + (t - self.t) * self.rate); self.t = t
        if self.tokens >= n:
            self.tokens -= n; return 0.0
        return (n - self.tokens) / self.rate


buckets = {m: Bucket(v) for m, v in ITPM.items()}
stats: dict = {}
app = FastAPI()


def count(model: str, what: str) -> None:
    phase = "outage" if OUTAGE[0] <= now() < OUTAGE[1] else ("before" if now() < OUTAGE[0] else "after")
    s = stats.setdefault(model, {})
    s[what] = s.get(what, 0) + 1
    s[phase + "_req"] = s.get(phase + "_req", 0) + (what == "req")


def err(code: int, typ: str, msg: str, headers=None) -> JSONResponse:
    return JSONResponse(status_code=code, content={"type": "error", "error": {"type": typ, "message": msg}}, headers=headers)


@app.post("/v1/messages")
async def messages(req: Request):
    body = await req.json()
    model = body["model"]
    count(model, "req")
    n_in = sum(len(m["content"]) for m in body["messages"]) // 4
    if model == "main" and OUTAGE[0] <= now() < OUTAGE[1] and rnd.random() < FAIL_RATE:
        count(model, "529")
        return err(529, "overloaded_error", "Overloaded")
    w = buckets[model].take(n_in)
    if w:
        count(model, "429")
        return err(429, "rate_limit_error", "input tokens per minute", {"retry-after-ms": str(int(w / SCALE * 1000) + 1)})
    if model == "main" and rnd.random() < HANG_RATE:
        count(model, "hang")
        await asyncio.sleep(HANG_SEC / SCALE)
    await asyncio.sleep(OUT / GEN_TPS[model] / SCALE)
    count(model, "ok")
    return {"id": "msg_fake", "type": "message", "role": "assistant", "model": model,
            "content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn", "stop_sequence": None,
            "usage": {"input_tokens": n_in, "output_tokens": OUT, "cache_read_input_tokens": 0, "cache_creation_input_tokens": 0}}


@app.get("/stats")
async def get_stats():
    return stats


@app.post("/reset")
async def reset():
    global t0, buckets
    stats.clear(); t0 = time.monotonic(); buckets = {m: Bucket(v) for m, v in ITPM.items()}
    rnd.seed(int(os.environ.get("SEED", "1")))
    return {}
