import asyncio, time
from fastapi import FastAPI
app = FastAPI()
@app.get("/sync_sleep")
def sync_sleep(): time.sleep(1); return {"ok": 1}
@app.get("/async_block")
async def async_block(): time.sleep(1); return {"ok": 1}
@app.get("/async_ok")
async def async_ok(): await asyncio.sleep(1); return {"ok": 1}
