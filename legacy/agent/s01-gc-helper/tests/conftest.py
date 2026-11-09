import httpx
import pytest
from fastapi.testclient import TestClient

import main

PAUSE_LINE = "[2026-11-08T20:05:00.000+0800][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 512M->128M(1024M) {ms}ms"

def fake_log(*ms: float) -> str:
    return "\n".join(PAUSE_LINE.format(ms=f"{m:.3f}") for m in ms)

@pytest.fixture
def agent_logs():
    """每个测试一份新的“日志代理数据”，测试里可以随便改。"""
    return {"order-01": fake_log(12.0, 244.05), "order-02": fake_log(80.4),
            "order-03": "", "order-04": None, "order-05": fake_log(41.0)}   # None = 卡住不回

@pytest.fixture
def client(agent_logs):
    async def handler(request: httpx.Request) -> httpx.Response:
        host = request.url.path.split("/")[1]
        if agent_logs[host] is None:
            raise httpx.ReadTimeout("agent hung", request=request)
        return httpx.Response(200, text=agent_logs[host])

    fake = httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://agent")
    main.app.dependency_overrides[main.get_client] = lambda: fake
    with TestClient(main.app) as c:          # with：会跑 lifespan
        yield c
    main.app.dependency_overrides.clear()     # 收尾：别让替身漏到别的测试
