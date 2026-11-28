"""最小 AI 网关：对 worker 来说它就是供应商（Anthropic Messages 格式），里面一层层做：

  认虚拟 key → 查这个租户能用哪些模型 → 模型别名换成真模型 → 预算和 token 限流 → 脱敏
  → 过滤请求里的工具 → 带真 key 转发（主模型 529 就换备用）→ 检查回复里的 tool_use
  → 按 usage 记账（流式要等最后的 message_delta）→ 写一行审计日志

uvicorn gateway:app --port 18740
"""
import json
import os
import re
import time

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse

UPSTREAM = os.environ.get("UPSTREAM", "http://127.0.0.1:18741")
UPSTREAM_KEY = os.environ.get("UPSTREAM_KEY", "sk-real-only-in-gateway")   # 真 key 只在这里
AUDIT = os.environ.get("AUDIT_LOG", os.path.join(os.path.dirname(__file__), "audit.jsonl"))
PRICE = {"small": (1.0, 5.0), "main": (3.0, 15.0), "backup": (1.0, 5.0)}   # 每百万 token 的美元：输入、输出（本页假设）

# 虚拟 key → 租户配置。线上放数据库或配置中心，这里写死
KEYS = {
    "vk-trade-7f3a": {"tenant": "trade", "models": {"triage", "investigate"}, "tools": {"search_logs", "parse_gc_log", "thread_dump", "search_notes", "heap_dump"}, "budget": 50.0},
    "vk-pay-19c2": {"tenant": "pay", "models": {"triage", "investigate"}, "tools": {"search_logs", "parse_gc_log", "thread_dump", "search_notes"}, "budget": 30.0},
    "vk-search-04be": {"tenant": "search", "models": {"triage"}, "tools": {"search_logs", "search_notes"}, "budget": 0.0005},
}
ROUTES = {"triage": ["small"], "investigate": ["main", "backup"]}          # 别名 → 真模型，按顺序降级
PII = [(re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)"), "[手机号]"), (re.compile(r"(?<!\d)\d{16,19}(?!\d)"), "[卡号]")]

spent: dict[str, float] = {}
meter: dict[str, dict] = {}
app = FastAPI()
http = httpx.AsyncClient(base_url=UPSTREAM, timeout=60)


def err(code, typ, msg):
    return JSONResponse(status_code=code, content={"type": "error", "error": {"type": typ, "message": msg}})


def redact(body: dict) -> int:
    n = 0
    for m in body["messages"]:
        if isinstance(m["content"], str):
            for pat, rep in PII:
                m["content"], k = pat.subn(rep, m["content"]); n += k
        else:
            for b in m["content"]:
                for field in ("text", "content"):
                    if isinstance(b.get(field), str):
                        for pat, rep in PII:
                            b[field], k = pat.subn(rep, b[field]); n += k
    return n


def account(tenant: str, model: str, usage: dict) -> float:
    pi, po = PRICE[model]
    cost = (usage.get("input_tokens", 0) * pi + usage.get("cache_read_input_tokens", 0) * pi * 0.1
            + usage.get("output_tokens", 0) * po) / 1e6
    spent[tenant] = spent.get(tenant, 0.0) + cost
    m = meter.setdefault(tenant, {"calls": 0, "input": 0, "output": 0, "cost": 0.0})
    m["calls"] += 1; m["input"] += usage.get("input_tokens", 0); m["output"] += usage.get("output_tokens", 0); m["cost"] += cost
    return cost


def audit(**kv) -> None:
    with open(AUDIT, "a", encoding="utf-8") as f:
        f.write(json.dumps({"ts": round(time.time(), 3), **kv}, ensure_ascii=False) + "\n")


@app.post("/v1/messages")
async def messages(req: Request):
    # anthropic SDK 2.x 把 key 放在 Authorization: Bearer 里，老客户端用 x-api-key：两种都认
    x_api_key = req.headers.get("x-api-key") or req.headers.get("authorization", "").removeprefix("Bearer ")
    t0 = time.perf_counter()
    cfg = KEYS.get(x_api_key)
    if cfg is None:                                                     # 1 认虚拟 key
        audit(status=401, key=x_api_key[:6])
        return err(401, "authentication_error", "unknown virtual key")
    tenant = cfg["tenant"]
    body = await req.json()
    alias = body.get("model")
    if alias not in cfg["models"]:                                      # 2 这个租户能用哪些模型
        audit(status=403, tenant=tenant, alias=alias)
        return err(403, "permission_error", f"model alias {alias!r} not allowed for {tenant}")
    if spent.get(tenant, 0.0) >= cfg["budget"]:                         # 3 预算（token 限流见上一页，这里只放预算）
        audit(status=429, tenant=tenant, alias=alias, reason="budget")
        return err(429, "rate_limit_error", f"{tenant} daily budget ${cfg['budget']} used up")
    n_pii = redact(body)                                                # 4 脱敏：出公司之前打码
    removed = [t["name"] for t in body.get("tools", []) if t["name"] not in cfg["tools"]]
    if "tools" in body:                                                 # 5 请求里的工具按白名单过滤
        body["tools"] = [t for t in body["tools"] if t["name"] in cfg["tools"]]
        if not body["tools"]:
            body.pop("tools")
    stream = bool(body.get("stream"))
    for model in ROUTES[alias]:                                         # 6 别名换真模型，带真 key 转发，529 就降级
        body["model"] = model
        hdr = {"x-api-key": UPSTREAM_KEY, "anthropic-version": "2023-06-01"}
        if not stream:
            r = await http.post("/v1/messages", json=body, headers=hdr)
            if r.status_code in (529, 500, 502, 503) and model != ROUTES[alias][-1]:
                continue
            if r.status_code != 200:
                audit(status=r.status_code, tenant=tenant, alias=alias, model=model)
                return JSONResponse(status_code=r.status_code, content=r.json())
            msg = r.json()
            blocked = []
            for i, b in enumerate(msg["content"]):                     # 7 回复里的 tool_use 再查一遍
                if b["type"] == "tool_use" and b["name"] not in cfg["tools"]:
                    blocked.append(b["name"])
                    msg["content"][i] = {"type": "text", "text": f"[网关] 工具 {b['name']} 不在 {tenant} 的白名单里，已拦下。"}
            if blocked and msg["stop_reason"] == "tool_use":
                msg["stop_reason"] = "end_turn"
            cost = account(tenant, model, msg["usage"])                 # 8 记账
            audit(status=200, tenant=tenant, alias=alias, model=model, tools_removed=removed, tool_use_blocked=blocked,
                  redacted=n_pii, usage=msg["usage"], cost=round(cost, 6), ms=round((time.perf_counter() - t0) * 1000, 1))
            return JSONResponse(content=msg, headers={"x-gateway-model": model})
        # 流式：原样转发字节，同时解析事件拿 usage
        up = await http.send(http.build_request("POST", "/v1/messages", json=body, headers=hdr), stream=True)
        if up.status_code in (529, 500, 502, 503) and model != ROUTES[alias][-1]:
            await up.aclose(); continue

        async def relay(up=up, model=model):
            usage, buf = {}, ""
            async for chunk in up.aiter_text():
                yield chunk
                buf += chunk
                while "\n\n" in buf:
                    ev, buf = buf.split("\n\n", 1)
                    for line in ev.splitlines():
                        if line.startswith("data: "):
                            d = json.loads(line[6:])
                            if d["type"] == "message_start":
                                usage.update(d["message"]["usage"])
                            elif d["type"] == "message_delta":
                                usage.update(d["usage"])               # 输出 token 在这里才最终确定
            await up.aclose()
            cost = account(tenant, model, usage)
            audit(status=200, tenant=tenant, alias=alias, model=model, stream=True, usage=usage, cost=round(cost, 6),
                  redacted=n_pii, ms=round((time.perf_counter() - t0) * 1000, 1))
        return StreamingResponse(relay(), media_type="text/event-stream", headers={"x-gateway-model": model})


@app.get("/meter")
async def get_meter():
    return meter
