"""假供应商：Anthropic Messages 格式，三个模型 small / main / backup，支持流式。

- 只认网关手里的真 key（UPSTREAM_KEY），别的 key 一律 401。
- 用户消息里出现“heap_dump”时，模型会“想调” heap_dump，哪怕请求的 tools 里没有它（模拟模型被日志里的话带偏）。
- MAIN_DOWN=1 时 main 一律回 529。
- 最近一次收到的请求体存在 /last，演示脱敏时看“供应商到底收到了什么”。

uvicorn upstream:app --port 18741
"""
import asyncio
import json
import os

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse

KEY = os.environ.get("UPSTREAM_KEY", "sk-real-only-in-gateway")
state = {"main_down": os.environ.get("MAIN_DOWN") == "1", "last": None, "calls": {}}
app = FastAPI()


def reply(body: dict) -> tuple[list, dict]:
    text = "".join(m["content"] if isinstance(m["content"], str) else
                   "".join(b.get("text", "") or json.dumps(b.get("content", "")) for b in m["content"])
                   for m in body["messages"])
    n_in = max(1, len(text) // 4)
    if "heap_dump" in text:
        content = [{"type": "tool_use", "id": "toolu_1", "name": "heap_dump", "input": {"host": "pay-02"}}]
    elif body.get("tools"):
        content = [{"type": "tool_use", "id": "toolu_1", "name": body["tools"][0]["name"], "input": {"q": "order-04"}}]
    else:
        content = [{"type": "text", "text": f"[{body['model']}] GC 停顿导致 P99 升高。"}]
    return content, {"input_tokens": n_in, "output_tokens": 40, "cache_read_input_tokens": 0, "cache_creation_input_tokens": 0}


@app.post("/v1/messages")
async def messages(req: Request):
    # anthropic SDK 2.x 把 key 放在 Authorization: Bearer 里，老客户端用 x-api-key：两种都认
    x_api_key = req.headers.get("x-api-key") or req.headers.get("authorization", "").removeprefix("Bearer ")
    if x_api_key != KEY:
        return JSONResponse(status_code=401, content={"type": "error", "error": {"type": "authentication_error", "message": "invalid x-api-key"}})
    body = await req.json()
    state["last"] = body
    m = body["model"]
    state["calls"][m] = state["calls"].get(m, 0) + 1
    if m == "main" and state["main_down"]:
        return JSONResponse(status_code=529, content={"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}})
    content, usage = reply(body)
    stop = "tool_use" if content[0]["type"] == "tool_use" else "end_turn"
    msg = {"id": "msg_up", "type": "message", "role": "assistant", "model": m, "content": content,
           "stop_reason": stop, "stop_sequence": None, "usage": usage}
    if not body.get("stream"):
        await asyncio.sleep(0.05)
        return msg

    async def sse():
        def ev(t, d):
            return f"event: {t}\ndata: {json.dumps(d, ensure_ascii=False)}\n\n"
        start = {**msg, "content": [], "stop_reason": None, "usage": {**usage, "output_tokens": 1}}
        yield ev("message_start", {"type": "message_start", "message": start})
        text = content[0].get("text", "")
        yield ev("content_block_start", {"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}})
        for i in range(0, len(text), 4):
            await asyncio.sleep(0.01)
            yield ev("content_block_delta", {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": text[i:i + 4]}})
        yield ev("content_block_stop", {"type": "content_block_stop", "index": 0})
        # 输出 token 数在最后的 message_delta 里才给：网关要等到这一条才能记账
        yield ev("message_delta", {"type": "message_delta", "delta": {"stop_reason": "end_turn", "stop_sequence": None},
                                   "usage": {"output_tokens": usage["output_tokens"]}})
        yield ev("message_stop", {"type": "message_stop"})
    return StreamingResponse(sse(), media_type="text/event-stream")


@app.get("/last")
async def last():
    return state["last"]


@app.post("/control")
async def control(req: Request):
    state.update(await req.json())
    return {k: v for k, v in state.items() if k != "last"}
