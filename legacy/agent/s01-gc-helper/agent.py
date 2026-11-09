# 假日志代理：按路径返回 GC 日志，order-04 卡住不回
import asyncio
from pathlib import Path
from fastapi import FastAPI
from fastapi.responses import PlainTextResponse

app = FastAPI()
DELAYS = {"order-01": .12, "order-02": .08, "order-03": .15, "order-04": 3600, "order-05": .09}

@app.get("/{host}/gc.log", response_class=PlainTextResponse)
async def gc_log(host: str, minutes: int = 10):
    await asyncio.sleep(DELAYS.get(host, 0))
    return Path(f"logs/{host}.log").read_text()
