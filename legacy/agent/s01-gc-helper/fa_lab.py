import asyncio, functools, time, itertools, json, warnings
warnings.filterwarnings("ignore")
from fastapi import FastAPI
from fastapi.testclient import TestClient
out = {}
for order, wraps, kind in itertools.product(["get_top", "timed_top"], [True, False], ["async", "sync"]):
    app = FastAPI(); calls = []
    def timed(fn):
        if kind == "async":
            async def wrapper(*a, **k):
                t = time.perf_counter(); r = await fn(*a, **k); calls.append(round((time.perf_counter()-t)*1000)); return r
        else:
            def wrapper(*a, **k):
                t = time.perf_counter(); r = fn(*a, **k); calls.append(round((time.perf_counter()-t)*1000, 3)); return r
        return functools.wraps(fn)(wrapper) if wraps else wrapper
    async def ep(service: str):
        await asyncio.sleep(0.1); return {"service": service}
    if order == "get_top": app.get("/x")(timed(ep))
    else: timed(app.get("/x")(ep))
    try:
        r = TestClient(app, raise_server_exceptions=False).get("/x?service=order")
        res = (r.status_code, r.text[:90])
    except Exception as e:
        res = ("EXC", repr(e)[:90])
    out[f"{order}|wraps={wraps}|{kind}"] = (res, calls)
for k, v in out.items(): print(k, v)
