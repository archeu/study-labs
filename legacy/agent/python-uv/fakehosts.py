# 假装 5 台机器上的日志代理：GET /order-0N/gc.log 返回最近的 GC 日志，各台响应时间不同
import asyncio, random
from fastapi import FastAPI
from fastapi.responses import PlainTextResponse

app = FastAPI()
DELAY = {"order-01": 0.12, "order-02": 0.08, "order-03": 0.15, "order-04": 5.0, "order-05": 0.09}

@app.get("/{host}/gc.log", response_class=PlainTextResponse)
async def gc_log(host: str, minutes: int = 10):
    await asyncio.sleep(DELAY[host])
    rnd = random.Random(host)
    lines = []
    for i in range(minutes * 6):
        ms = round(rnd.uniform(4, 40) if i % 17 else rnd.uniform(80, 260), 3)
        lines.append(f"[2026-11-08T20:{i % 60:02d}:03.512+0800][info][gc] GC({1200+i}) Pause Young (Normal) (G1 Evacuation Pause) 412M->188M(1024M) {ms}ms")
        lines.append(f"[2026-11-08T20:{i % 60:02d}:03.600+0800][info][gc,cpu] GC({1200+i}) User=0.05s Sys=0.00s Real={ms/1000:.3f}s")
    return "\n".join(lines) + "\n"
