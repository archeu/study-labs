def test_summary_marks_hung_host(client):
    r = client.get("/gc/summary", params={"service": "order", "minutes": 10})
    assert r.status_code == 200
    hosts = {h["host"]: h for h in r.json()["hosts"]}
    assert hosts["order-01"]["max_ms"] == 244.05
    assert hosts["order-03"]["error"] == "no pauses"
    assert hosts["order-04"]["error"] is not None

def test_unknown_service_is_404(client):
    r = client.get("/gc/summary", params={"service": "pay"})
    assert r.status_code == 404
    assert r.json() == {"detail": "unknown service: pay"}

def test_minutes_out_of_range_is_422(client):
    r = client.get("/gc/summary", params={"service": "order", "minutes": 0})
    assert r.status_code == 422
    assert r.json()["detail"][0]["loc"] == ["query", "minutes"]

def test_agent_change_only_affects_this_test(client, agent_logs):
    agent_logs["order-02"] = ""
    r = client.get("/gc/order-02")
    assert r.json()["error"] == "no pauses"
