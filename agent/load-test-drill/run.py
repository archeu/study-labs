"""压测（1×、2×、3× 的告警量）+ 三次故障演练（模型超时、被限流、工具挂掉），打出一张报告表。

uv run python load-test-drill/run.py      # 约 4 分钟（场景时间约 2 小时，30 倍速）
"""
import asyncio
import os
import subprocess
import sys
import time

import httpx

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from harness import run_night  # noqa: E402

PORT = 18770
BASE = f"http://127.0.0.1:{PORT}"

PLAN = [
    ("压测 1×（87 个）", dict(mult=1)),
    ("压测 2×（174 个）", dict(mult=2)),
    ("压测 3×（261 个）", dict(mult=3)),
    ("演练 1：模型超时", dict(mult=1, events=[(120, {"hang_rate": 0.3, "hang_sec": 300}), (300, {"hang_rate": 0.0})])),
    ("演练 2：额度砍半", dict(mult=1, events=[(120, {"main_itpm": 200_000}), (480, {"main_itpm": 400_000})])),
    ("演练 3：查日志的工具挂了", dict(mult=1, tool_down=(120, 300))),
]


def main():
    env = {**os.environ, "OUTAGE_START": "99999", "OUTAGE_END": "99999", "HANG_RATE": "0"}
    p = subprocess.Popen([sys.executable, "-m", "uvicorn", "fake_provider:app", "--port", str(PORT), "--log-level", "warning"],
                         cwd=HERE, env=env)
    try:
        for _ in range(50):
            try:
                httpx.get(BASE + "/stats", timeout=0.5); break
            except httpx.HTTPError:
                time.sleep(0.2)
        print(f"{'场景':<16}{'事件':>4}{'做完':>5}{'转人工':>6}{'P0':>4}{'全部做完':>8}{'P50':>6}{'P95':>6}{'P0最长':>6}"
              f"{'最大积压':>7}{'429':>5}{'529':>5}{'超时':>5}{'备用':>5}{'缺证据':>6}{'花费':>8}")
        for name, kw in PLAN:
            httpx.post(BASE + "/control", json={"hang_rate": 0.0, "main_itpm": 400_000})
            r = asyncio.run(run_night(BASE, **kw))
            print(f"{name:<16}{r['incidents']:>4}{r['done']:>5}{r['escalated']:>6}{r['escalated_p0']:>4}{r['end_min']:>7.1f}分"
                  f"{r['p50']:>5.1f}分{r['p95']:>5.1f}分{r['p0_max']:>5.1f}分{r['lag_max']:>7}{r['r429']:>5}{r['r529']:>5}"
                  f"{r['timeouts']:>5}{r['backup']:>5}{r['missing']:>6}{'$%.2f' % r['usd']:>8}", flush=True)
            if r["breaker"]:
                print("   熔断器：" + " → ".join(f"{s}@{t:.1f}分" for t, s in r["breaker"][:6]) + (" …" if len(r["breaker"]) > 6 else ""))
    finally:
        p.terminate(); p.wait()


if __name__ == "__main__":
    main()
