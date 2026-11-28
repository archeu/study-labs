"""页面的两条底线：模型输出只当文字；GET 永远不触发调查。"""
import os

from fastapi.testclient import TestClient

import app as ui


def test_page_never_uses_innerhtml():
    html = open(os.path.join(os.path.dirname(__file__), "static", "index.html"), encoding="utf-8").read()
    assert "innerHTML" not in html and "textContent" in html


def test_get_does_not_start_and_second_start_is_refused():
    c = TestClient(ui.app)
    inc = "trade-order-OrderApiP99High"
    c.get("/api/incidents")
    assert inc not in ui.running
    assert c.post(f"/api/incidents/{inc}/start", headers={"Idempotency-Key": "a"}).json()["started"]
    assert not c.post(f"/api/incidents/{inc}/start", headers={"Idempotency-Key": "b"}).json()["started"]
