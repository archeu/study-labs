from fastapi.testclient import TestClient
import main

async def fake_fetch(client, host, minutes):
    if host == "order-04":
        return main.HostGc(host=host, error="timeout 1s")
    return main.parse(host, "[..][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 412M->188M(1024M) 12.500ms\n")

def test_summary_marks_timeout(monkeypatch):
    monkeypatch.setattr(main, "fetch", fake_fetch)
    r = TestClient(main.app).get("/gc/summary", params={"service": "order", "minutes": 10})
    assert r.status_code == 200
    hosts = {h["host"]: h for h in r.json()["hosts"]}
    assert hosts["order-04"]["error"] == "timeout 1s"
    assert hosts["order-01"]["max_ms"] == 12.5

def test_minutes_out_of_range():
    r = TestClient(main.app).get("/gc/summary", params={"service": "order", "minutes": 0})
    assert r.status_code == 422
