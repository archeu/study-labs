"""接入服务：收 Alertmanager 风格的 webhook，认租户、去重、合并成事件，写进队列，马上回 202。
另有 GET /incidents/{id}/events：把这个事件的进度用 SSE 推给网页，支持 Last-Event-ID 断点续读。

uvicorn ingress:app --port 18710
"""
import asyncio

import redis.asyncio as aredis
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import StreamingResponse

from common import REDIS_URL, STREAMS, incident_key

TOKENS = {"tk-trade": "trade", "tk-pay": "pay", "tk-search": "search"}   # Bearer token → 租户
PRIO = {"critical": 0, "warning": 1, "info": 2}

app = FastAPI()
rc = aredis.Redis.from_url(REDIS_URL, decode_responses=True)


@app.post("/alerts", status_code=202)
async def alerts(req: Request, authorization: str = Header("")):
    tenant = TOKENS.get(authorization.removeprefix("Bearer "))
    if tenant is None:
        raise HTTPException(401, "unknown token")
    body = await req.json()
    dup = new_inc = merged = 0
    created = []
    for al in body["alerts"]:
        # 1) 按指纹去重：推送方重发的同一条告警，10 分钟内只认一次
        if not await rc.set(f"fp:{al['fingerprint']}", 1, nx=True, ex=600):
            dup += 1
            continue
        al["tenant"] = tenant
        key = incident_key(al)
        # 2) 合并：同一租户、服务、告警名，5 分钟窗口内只起一个事件
        inc_id = f"{tenant}-{al['labels']['service']}-{al['labels']['alertname']}"
        if await rc.set(f"inc:{key}", inc_id, nx=True, ex=900):
            prio = PRIO[al["labels"].get("severity", "info")]
            await rc.xadd(STREAMS[prio], {"incident": inc_id, "tenant": tenant, "priority": f"P{prio}"})
            new_inc += 1
            created.append(inc_id)
        else:
            merged += 1
    # 3) 不等调查：任务已经进队列，马上回 202
    return {"accepted": len(body["alerts"]), "duplicates": dup, "merged": merged, "new_incidents": created}


@app.get("/incidents/{inc}/events")
async def events(inc: str, last_event_id: str = Header("0-0"), once: bool = False):
    async def gen():
        last = last_event_id
        while True:
            got = await rc.xread({f"progress:{inc}": last}, count=50, block=1000)
            for _, entries in got or []:
                for eid, f in entries:
                    last = eid
                    # Redis 流的条目 id 直接当 SSE 的 id：浏览器断线重连时会带着它回来
                    yield f"id: {eid}\nevent: progress\ndata: {f['msg']}\n\n"
                    if f["msg"] == "报告已出":
                        return
            if once and not got:
                return
            await asyncio.sleep(0)
    return StreamingResponse(gen(), media_type="text/event-stream")
