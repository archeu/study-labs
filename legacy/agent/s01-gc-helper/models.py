from fastapi import FastAPI, Query
from pydantic import BaseModel, ConfigDict, Field

class HostGc(BaseModel):                          # ≈ 一个 DTO
    model_config = ConfigDict(extra="forbid")    # 多出来的字段直接报错
    host: str
    pauses: int = Field(0, ge=0)
    max_ms: float | None = None
    avg_ms: float | None = None
    error: str | None = None

class GcSummary(BaseModel):
    service: str
    minutes: int
    hosts: list[HostGc]

app = FastAPI()

@app.get("/gc/summary", response_model=GcSummary)
async def gc_summary(service: str, minutes: int = Query(10, ge=1, le=60)):
    raw = {"host": "order-01", "pauses": "60", "max_ms": "244.05", "avg_ms": 26.1}  # 日志代理返回的原始字典
    return {                                     # 故意多带一个内部字段
        "service": service, "minutes": minutes,
        "hosts": [HostGc(**raw), HostGc(host="order-04", error="timeout 1s")],
        "agent_token": "s3cr3t",
    }

@app.get("/gc/leak")                             # 没写 response_model
async def gc_leak(service: str):
    return {"service": service, "agent_token": "s3cr3t"}

@app.get("/gc/bad", response_model=GcSummary)
async def gc_bad(service: str):
    return {"service": service, "minutes": "ten", "hosts": []}
