"""线上指标：把评测里存下的真实耗时、token、对错，按一天的线上流量回放，用 OpenTelemetry 指标 SDK 记下来，
再像 Prometheus 那样从直方图里算 P95、按提示词版本拆开对比。

    uv run python online-metrics/main.py replay --canary pr57-@4-r1           # 假如 @4 没过评测就灰度了
    uv run python online-metrics/main.py replay --canary fix-@5-r1            # @5 灰度 5%
    uv run python online-metrics/main.py replay --canary fix-@5-r1 --share 50 --days 3
    uv run python online-metrics/main.py buckets base-@3-r1                   # 直方图算出的 P95 和真实 P95 差多少

假设（写在这里，页面上也标“估算”）：
  流量：一天 480 次排障（平均 3 分钟一次告警），--share 是灰度比例（默认 5%）。
  每次请求随机抽一条用例，再从这个版本存下的运行里随机取一次结果：耗时、token、对错都是真跑出来的。
  值班同学的反应（线上没有标准答案，只能看人的行为）：
    结论通过（判分全对）     → 90% 采纳、5% 转人工
    说中了但没全对            → 50% 采纳、25% 转人工
    其余（没说中、没看现场…） → 15% 采纳、60% 转人工
  “成功率”= 采纳率；“转人工率”= 值班同学放弃助手、自己从头查的比例。
"""

import json
import math
import random
import sys
from collections import defaultdict
from pathlib import Path

from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import InMemoryMetricReader
from opentelemetry.sdk.metrics.view import ExplicitBucketHistogramAggregation, View

HERE = Path(__file__).resolve().parent
RUNS = HERE.parent / "eval-dataset" / "runs"
# 约定里 gen_ai.invoke_agent.duration 的建议桶边界（秒）
BUCKETS = [0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120, 300, 600, 1800, 3600, 7200, 21600, 43200, 86400]
REACT = {"pass": (0.90, 0.05), "hit": (0.50, 0.25), "miss": (0.15, 0.60)}


def load(run):
    out = defaultdict(list)
    for f in sorted((RUNS / run).glob("[0-9][0-9]-*.json")):
        rec = json.loads(f.read_text())
        chats = [s for s in rec["spans"] if s["kind"] == "chat"]
        g = rec["grade"]
        out[rec["case"]].append({
            "secs": max(s["start_ms"] + s["ms"] for s in rec["spans"]) / 1000,
            "in": sum(s["input_tokens"] + s["cache_read_tokens"] for s in chats),
            "cache": sum(s["cache_read_tokens"] for s in chats),
            "out": sum(s["output_tokens"] for s in chats),
            "kind": "pass" if g["通过"] else "hit" if g["说中"] else "miss",
            "version": rec["prompt"]})
    return out


def merge(*pools):
    m = defaultdict(list)
    for p in pools:
        for k, v in p.items():
            m[k] += v
    return m


def make_meter():
    reader = InMemoryMetricReader()
    views = [View(instrument_name="gen_ai.invoke_agent.duration", aggregation=ExplicitBucketHistogramAggregation(BUCKETS))]
    mp = MeterProvider(metric_readers=[reader], views=views)
    meter = mp.get_meter("oncall.metrics")
    return reader, {
        "dur": meter.create_histogram("gen_ai.invoke_agent.duration", unit="s"),
        "in": meter.create_counter("gen_ai.client.inference.usage.input_tokens", unit="{token}"),
        "out": meter.create_counter("gen_ai.client.inference.usage.output_tokens", unit="{token}"),
        "req": meter.create_counter("oncall.requests", unit="{request}"),   # 自定义：按结局计数
    }


def collect(reader):
    """把 SDK 里的数读出来，按 prompt_version 分组：直方图的桶、计数器的和。"""
    res = defaultdict(lambda: {"buckets": None, "count": 0, "sum": 0.0, "in": 0, "out": 0, "outcome": defaultdict(int)})
    for rm in reader.get_metrics_data().resource_metrics:
        for sm in rm.scope_metrics:
            for m in sm.metrics:
                for dp in m.data.data_points:
                    a = dict(dp.attributes)
                    r = res[a["prompt_version"]]
                    if m.name == "gen_ai.invoke_agent.duration":
                        r["buckets"], r["count"], r["sum"] = list(dp.bucket_counts), dp.count, dp.sum
                    elif m.name == "gen_ai.client.inference.usage.input_tokens":
                        r["in"] += dp.value
                    elif m.name == "gen_ai.client.inference.usage.output_tokens":
                        r["out"] += dp.value
                    elif m.name == "oncall.requests":
                        r["outcome"][a["outcome"]] += dp.value
    return res


def hist_quantile(q, bounds, counts):
    """和 Prometheus 的 histogram_quantile 一样：找到分位所在的桶，在桶内线性插值。"""
    total = sum(counts)
    rank, cum = q * total, 0
    for i, c in enumerate(counts):
        if cum + c >= rank and c:
            lo = bounds[i - 1] if i > 0 else 0
            hi = bounds[i] if i < len(bounds) else bounds[-1]
            return lo + (hi - lo) * (rank - cum) / c
        cum += c
    return bounds[-1]


def wilson(k, n, z=1.96):
    if n == 0:
        return (0, 1)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0, c - h), min(1, c + h))


def replay(prod_pool, canary_pool, share=0.05, days=1, per_day=480, seed=7):
    rnd = random.Random(seed)
    reader, ins = make_meter()
    cases = sorted(set(prod_pool) & set(canary_pool))
    exact = defaultdict(list)
    for _ in range(per_day * days):
        canary = rnd.random() < share
        pool = canary_pool if canary else prod_pool
        r = rnd.choice(pool[rnd.choice(cases)])
        v = r["version"]
        adopt_p, esc_p = REACT[r["kind"]]
        x = rnd.random()
        outcome = "adopted" if x < adopt_p else "escalated" if x < adopt_p + esc_p else "ignored"
        attrs = {"prompt_version": v}
        ins["dur"].record(r["secs"], attrs)
        ins["in"].add(r["in"], attrs)
        ins["out"].add(r["out"], attrs)
        ins["req"].add(1, {**attrs, "outcome": outcome})
        exact[v].append(r["secs"])
    res = collect(reader)
    rows = {}
    for v, r in sorted(res.items()):
        n = r["count"]
        ok, esc = r["outcome"]["adopted"], r["outcome"]["escalated"]
        lo, hi = wilson(ok, n)
        xs = sorted(exact[v])
        rows[v] = {"请求": n, "成功率": round(ok / n * 100, 1), "成功率区间": [round(lo * 100), round(hi * 100)],
                   "转人工率": round(esc / n * 100, 1), "平均输入token": round(r["in"] / n), "平均输出token": round(r["out"] / n),
                   "P95(直方图)": round(hist_quantile(0.95, BUCKETS, r["buckets"]), 1),
                   "P95(真实)": round(xs[min(len(xs) - 1, int(math.ceil(0.95 * len(xs))) - 1)], 1), "平均秒": round(r["sum"] / n, 1)}
    return rows


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "help"
    arg = lambda f, d=None: sys.argv[sys.argv.index(f) + 1] if f in sys.argv else d  # noqa: E731
    if cmd == "replay":
        prod = merge(*[load(r) for r in arg("--prod", "base-@3-r1,base-@3-r2").split(",") if (RUNS / r).exists()])
        canary = load(arg("--canary", "fix-@5-r1"))
        rows = replay(prod, canary, share=float(arg("--share", 5)) / 100, days=int(arg("--days", 1)))
        for v, r in rows.items():
            print(v, json.dumps(r, ensure_ascii=False))
    elif cmd == "buckets":
        pool = load(sys.argv[2])
        xs = sorted(x["secs"] for v in pool.values() for x in v)
        counts = [0] * (len(BUCKETS) + 1)
        for x in xs:
            counts[next((i for i, b in enumerate(BUCKETS) if x <= b), len(BUCKETS))] += 1
        print("桶边界（秒）:", BUCKETS[:11], "…")
        print("各桶计数:", counts[:12])
        print(f"真实 P95 {xs[int(math.ceil(.95 * len(xs))) - 1]:.1f}s，直方图插值 P95 {hist_quantile(.95, BUCKETS, counts):.1f}s；"
              f"真实 P50 {xs[len(xs) // 2]:.1f}s，直方图插值 P50 {hist_quantile(.5, BUCKETS, counts):.1f}s")
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
