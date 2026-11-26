"""模拟 Alertmanager 往 n8n 的 Webhook 发告警：同一个告警发三次（首次、状态变化重发、repeat_interval 重发），再发一个别的告警。
看值班助手的桩收到几次。

    python3 lowcode-platforms/send_alerts.py
"""

import json
import time
import urllib.request
from pathlib import Path

URL = "http://127.0.0.1:5678/webhook/alertmanager"
LOG = Path(__file__).parent / "data" / "incidents.jsonl"


def alert(fp: str, name: str, status: str = "firing") -> dict:
    # Alertmanager webhook 的格式：一次 POST 里是一组告警（按 group_by 分组），每条带 fingerprint
    return {"version": "4", "status": status, "receiver": "oncall-n8n", "groupKey": "{}:{alertname=\"%s\"}" % name,
            "alerts": [{"status": status, "fingerprint": fp, "startsAt": "2026-11-08T20:16:00+08:00",
                        "labels": {"alertname": name, "service": "order-svc", "severity": "critical"},
                        "annotations": {"summary": "order-04 /orders/{id} P99 2.1s（阈值 800ms）"}}]}


def post(payload: dict) -> float:
    t0 = time.perf_counter()
    req = urllib.request.Request(URL, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    urllib.request.urlopen(req).read()
    return (time.perf_counter() - t0) * 1000


if __name__ == "__main__":
    before = len(LOG.read_text().splitlines()) if LOG.exists() else 0
    sends = [("首次触发", alert("7f3a", "OrderApiP99High")),
             ("20:21 状态变化，重发", alert("7f3a", "OrderApiP99High")),
             ("4 小时后 repeat_interval 重发", alert("7f3a", "OrderApiP99High")),
             ("另一个告警", alert("9c21", "OrderDbSlowQuery"))]
    for label, payload in sends:
        ms = post(payload)
        print(f"发 {label:24} fingerprint={payload['alerts'][0]['fingerprint']}  Webhook 回应 {ms:.0f} ms")
        time.sleep(1.5)
    got = LOG.read_text().splitlines()[before:] if LOG.exists() else []
    print(f"\n发了 {len(sends)} 次，值班助手收到 {len(got)} 次：", [json.loads(l)["body"]["thread_id"] for l in got])
