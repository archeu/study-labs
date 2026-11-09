import asyncio, functools, time
from fastapi import FastAPI
from fastapi.testclient import TestClient

app = FastAPI()
calls = []

def timed_nowraps(fn):                       # 忘了 wraps
    async def wrapper(*args, **kwargs):
        t = time.perf_counter(); r = await fn(*args, **kwargs)
        calls.append(("nowraps", round((time.perf_counter() - t) * 1000))); return r
    return wrapper

def timed(fn):
    @functools.wraps(fn)
    async def wrapper(*args, **kwargs):
        t = time.perf_counter(); r = await fn(*args, **kwargs)
        calls.append((fn.__name__, round((time.perf_counter() - t) * 1000))); return r
    return wrapper

@app.get("/a")
@timed_nowraps
async def a(service: str, minutes: int = 10):
    await asyncio.sleep(0.1); return {"service": service, "minutes": minutes}

@app.get("/b")
@timed
async def b(service: str, minutes: int = 10):
    await asyncio.sleep(0.1); return {"service": service, "minutes": minutes}

@timed                                       # 写在 app.get 上面：登记的是没计时的原函数
@app.get("/c")
async def c(service: str):
    await asyncio.sleep(0.1); return {"service": service}

async def d(): return 1
print("app.get 返回原函数:", app.get("/d")(d) is d)

cl = TestClient(app)
for p in ("/a?service=order", "/b?service=order", "/c?service=order"):
    r = cl.get(p); print(p, r.status_code, r.text[:160])
print("calls", calls)