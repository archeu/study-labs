import asyncio, re, time
from contextlib import asynccontextmanager
from typing import Annotated

import httpx
from fastapi import APIRouter, Depends, FastAPI, HTTPException, Query, Request
from pydantic import BaseModel

AGENT = "http://127.0.0.1:9100"
SERVICES = {"order": [f"order-0{i}" for i in range(1, 6)]}
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

@asynccontextmanager
async def lifespan(app: FastAPI):                 # ≈ @Bean + @PreDestroy：整个进程共用一个连接池
    app.state.client = httpx.AsyncClient(base_url=AGENT, timeout=5.0)
    yield
    await app.state.client.aclose()

app = FastAPI(title="值班助手", lifespan=lifespan)
router = APIRouter(prefix="/gc", tags=["gc"])     # ≈ 类上的 @RequestMapping("/gc")

def get_client(request: Request) -> httpx.AsyncClient:   # 依赖：从 app.state 取共享的客户端
    return request.app.state.client

def get_hosts(service: str) -> list[str]:                 # 依赖：校验 service 并查出主机列表
    if service not in SERVICES:
        raise HTTPException(status_code=404, detail=f"unknown service: {service}")
    return SERVICES[service]

Client = Annotated[httpx.AsyncClient, Depends(get_client)]
Hosts = Annotated[list[str], Depends(get_hosts)]

def parse(host: str, text: str) -> HostGc:
    ms = [float(m.group(1)) for line in text.splitlines() if (m := PAUSE.search(line))]
    if not ms:
        return HostGc(host=host, error="no pauses")
    return HostGc(host=host, pauses=len(ms), max_ms=max(ms), avg_ms=round(sum(ms) / len(ms), 1))

async def fetch(client: httpx.AsyncClient, host: str, minutes: int) -> HostGc:
    try:
        async with asyncio.timeout(1.0):
            r = await client.get(f"/{host}/gc.log", params={"minutes": minutes})
    except TimeoutError:
        return HostGc(host=host, error="timeout 1s")
    except httpx.HTTPError as e:                  # httpx 自己的超时、连不上：也记成这一台出错
        return HostGc(host=host, error=type(e).__name__)
    return parse(host, r.text)

@router.get("/summary", response_model=GcSummary)
async def gc_summary(client: Client, hosts: Hosts, service: str,
                     minutes: Annotated[int, Query(ge=1, le=60)] = 10):
    t = time.perf_counter()
    results = await asyncio.gather(*(fetch(client, h, minutes) for h in hosts))
    return GcSummary(service=service, minutes=minutes, hosts=results,
                     elapsed_ms=round((time.perf_counter() - t) * 1000))

@router.get("/{host}", response_model=HostGc)     # 写在 /summary 后面
async def gc_host(host: str, client: Client, minutes: Annotated[int, Query(ge=1, le=60)] = 10):
    return await fetch(client, host, minutes)

app.include_router(router)

@app.middleware("http")                            # ≈ Filter：每个请求都经过
async def timing(request: Request, call_next):
    t = time.perf_counter()
    response = await call_next(request)
    response.headers["X-Process-Ms"] = f"{(time.perf_counter() - t) * 1000:.1f}"
    return response

@app.get("/healthz")
async def healthz():
    return {"ok": True}
