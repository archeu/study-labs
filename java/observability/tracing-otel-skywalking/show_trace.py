#!/usr/bin/env python3
"""从 Jaeger 的 v3 查询接口（OTLP JSON）取 trace，画成文本瀑布图。
Jaeger 2.x 去掉了旧的 /api/traces，查询走 /api/v3/traces。
用法：show_trace.py <jaeger 地址> <服务名> [min=2s 或 max=1s] [取几条]
"""
import base64, json, sys, time, urllib.parse, urllib.request
from datetime import datetime, timedelta, timezone

base, service = sys.argv[1], sys.argv[2]
cond = sys.argv[3] if len(sys.argv) > 3 else ""
limit = int(sys.argv[4]) if len(sys.argv) > 4 else 1
now = datetime.now(timezone.utc)
q = {"query.service_name": service, "query.search_depth": "50",
     "query.start_time_min": (now - timedelta(hours=1)).isoformat(timespec="seconds").replace("+00:00", "Z"),
     "query.start_time_max": (now + timedelta(minutes=1)).isoformat(timespec="seconds").replace("+00:00", "Z")}
if cond.startswith("min="):
    q["query.duration_min"] = cond[4:]
# duration_max 匹配的是“trace 里有一个 span 不超过它”，几乎每条都会命中，所以 max 在下面按根 span 自己过滤
body = json.load(urllib.request.urlopen(base + "/api/v3/traces?" + urllib.parse.urlencode(q)))

KINDS = {0: "", 1: "INTERNAL", 2: "SERVER", 3: "CLIENT", 4: "PRODUCER", 5: "CONSUMER",
         "SPAN_KIND_INTERNAL": "INTERNAL", "SPAN_KIND_SERVER": "SERVER", "SPAN_KIND_CLIENT": "CLIENT"}

def hexid(v):
    if not v:
        return ""
    try:
        int(v, 16)
        return v.lower()
    except ValueError:
        return base64.b64decode(v).hex()

spans = []
for rs in body.get("result", {}).get("resourceSpans", []):
    svc = next((a["value"].get("stringValue") for a in rs["resource"]["attributes"] if a["key"] == "service.name"), "?")
    for ss in rs.get("scopeSpans", []):
        for s in ss.get("spans", []):
            attrs = {a["key"]: list(a["value"].values())[0] for a in s.get("attributes", [])}
            spans.append({"trace": hexid(s["traceId"]), "id": hexid(s["spanId"]), "parent": hexid(s.get("parentSpanId", "")),
                          "name": s["name"], "kind": KINDS.get(s.get("kind", 0), str(s.get("kind", ""))), "svc": svc,
                          "start": int(s["startTimeUnixNano"]), "end": int(s["endTimeUnixNano"]),
                          "thread": attrs.get("thread.name", "")})
traces = {}
for s in spans:
    traces.setdefault(s["trace"], []).append(s)
def secs(v):
    return float(v[:-2]) / 1000 if v.endswith("ms") else float(v.rstrip("s"))
def root_ok(ss):
    roots = [x for x in ss if x["name"].endswith("/api/orders") and not x["parent"]]
    if not roots:
        return False
    if cond.startswith("max="):
        return (roots[0]["end"] - roots[0]["start"]) / 1e9 <= secs(cond[4:])
    return True
order = [t for t, ss in traces.items() if root_ok(ss)]
order.sort(key=lambda t: -min(x["start"] for x in traces[t]))
for t in order[:limit]:
    ss = traces[t]
    t0 = min(x["start"] for x in ss); t1 = max(x["end"] for x in ss); total = max(1, t1 - t0)
    print(f"trace_id {t}  共 {len(ss)} 个 span，{len({x['svc'] for x in ss})} 个服务")
    ids = {x["id"] for x in ss}
    def walk(s, d):
        w = 36
        a = round((s["start"] - t0) / total * w); n = max(1, round((s["end"] - s["start"]) / total * w))
        name = ("  " * d + s["name"])[:42]
        print(f"{name:<42} {s['svc']:<12} {s['kind']:<8} {' ' * a + '█' * min(n, w - a):<36} {(s['end'] - s['start']) / 1e6:8.1f} ms  {s['thread']}")
        for c in sorted([x for x in ss if x["parent"] == s["id"]], key=lambda x: x["start"]):
            walk(c, d + 1)
    for r in sorted([x for x in ss if x["parent"] not in ids], key=lambda x: x["start"]):
        walk(r, 0)
    print()
