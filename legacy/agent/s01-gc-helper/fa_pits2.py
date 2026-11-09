import asyncio, time, warnings, threading
warnings.filterwarnings("ignore")
import httpx
from fastapi import FastAPI
from fastapi.testclient import TestClient

# 1 route order
bad = FastAPI()
@bad.get("/gc/{host}")
async def gc_host(host: str): return {"handler": "gc_host", "host": host}
@bad.get("/gc/summary")
async def gc_summary(service: str): return {"handler": "gc_summary"}
print("1 route order:", TestClient(bad).get("/gc/summary?service=order").json())

# 5 error as 200
e = FastAPI()
@e.get("/x")
async def x(): return {"error": "unknown service"}
print("5 error dict status:", TestClient(e).get("/x").status_code)

async def main():
    async with httpx.AsyncClient(base_url="http://127.0.0.1:8001", timeout=100) as c:
        for path, n in (("/sync_sleep", 50), ("/sync_sleep", 40), ("/async_block", 5), ("/async_ok", 50)):
            t = time.perf_counter()
            await asyncio.gather(*(c.get(path) for _ in range(n)))
            print(f"2 {n:3d} x {path:12s} {time.perf_counter()-t:.2f}s")
    # 3 client per request vs shared
    N = 200
    t = time.perf_counter()
    for _ in range(N):
        async with httpx.AsyncClient() as c: await c.get("http://127.0.0.1:8000/healthz")
    per = (time.perf_counter() - t) / N * 1000
    t = time.perf_counter()
    async with httpx.AsyncClient() as c:
        for _ in range(N): await c.get("http://127.0.0.1:8000/healthz")
    shared = (time.perf_counter() - t) / N * 1000
    print(f"3 new client each: {per:.2f} ms/req, shared: {shared:.2f} ms/req")
asyncio.run(main())
