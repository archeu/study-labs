import asyncio, re, time
import httpx
from fastapi import FastAPI, Query
from pydantic import BaseModel

app = FastAPI()
AGENT = "http://127.0.0.1:9100"
HOSTS = [f"order-0{i}" for i in range(1, 6)]
PAUSE = re.compile(r"Pause .* (\d+\.\d+)ms$")

class HostGc(BaseModel):
    host: str
    pauses: int = 0
    max_ms: float | None = None
    avg_ms: float | None = None
    error: str | None = None

class GcSummary(BaseModel):
    service: str
    minutes: int
    hosts: list[HostGc]
    elapsed_ms: int

def parse(host: str, text: str) -> HostGc:
    ms = [float(m.group(1)) for line in text.splitlines() if (m := PAUSE.search(line))]
    return HostGc(host=host, pauses=len(ms), max_ms=max(ms), avg_ms=round(sum(ms) / len(ms), 1))

async def fetch(client: httpx.AsyncClient, host: str, minutes: int) -> HostGc:
    try:
        async with asyncio.timeout(1.0):
            r = await client.get(f"{AGENT}/{host}/gc.log", params={"minutes": minutes})
    except TimeoutError:
        return HostGc(host=host, error="timeout 1s")
    return parse(host, r.text)

@app.get("/gc/summary", response_model=GcSummary)
async def gc_summary(service: str, minutes: int = Query(10, ge=1, le=60)):
    t0 = time.perf_counter()
    async with httpx.AsyncClient() as client:
        hosts = await asyncio.gather(*(fetch(client, h, minutes) for h in HOSTS))
    return GcSummary(service=service, minutes=minutes, hosts=hosts,
                     elapsed_ms=round((time.perf_counter() - t0) * 1000))

@app.get("/gc/summary-serial", response_model=GcSummary)
async def gc_summary_serial(service: str, minutes: int = Query(10, ge=1, le=60)):
    t0 = time.perf_counter()
    async with httpx.AsyncClient() as client:
        hosts = [await fetch(client, h, minutes) for h in HOSTS]
    return GcSummary(service=service, minutes=minutes, hosts=hosts,
                     elapsed_ms=round((time.perf_counter() - t0) * 1000))

@app.get("/gc/summary-blocking")
async def gc_summary_blocking():
    time.sleep(1.0)   # 埋雷：在 async def 里做阻塞调用
    return {"ok": True}

@app.get("/healthz")
async def healthz():
    return {"ok": True}
