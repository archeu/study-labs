"""压测脚本的两条不变式：1 倍的到达总数是 87；熔断器不把 429 记成失败（见 harness.call）。"""
import inspect

import harness


def test_arrivals_total():
    assert sum(harness.ARRIVALS) == 87 and len(harness.PRIO) == 87


def test_429_not_counted_by_breaker():
    src = inspect.getsource(harness.run_night)
    i429 = src.index("except anthropic.RateLimitError")
    i5xx = src.index("except (anthropic.APIConnectionError")
    assert "br.record(False)" not in src[i429:i5xx]
