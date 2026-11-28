"""熔断器的三条路：失败过半打开；打开期间不放行、到点半开；试探全成功关上、有一个失败再打开。"""
import os
import time

os.environ.setdefault("SCALE", "600")
from breaker import SCALE, CircuitBreaker  # noqa: E402


def test_open_halfopen_close():
    b = CircuitBreaker(window=10, min_calls=4, threshold=0.5, open_sec=30, probes=2)
    for ok in (True, False, False, True, False):
        b.record(ok)
    assert b.state == "OPEN" and not b.allow()
    time.sleep(31 / SCALE)
    assert b.allow() and b.state == "HALF_OPEN"
    assert b.allow() and not b.allow()          # 只放 2 个试探
    b.record(True); b.record(True)
    assert b.state == "CLOSED"


def test_probe_failure_reopens():
    b = CircuitBreaker(window=10, min_calls=2, threshold=0.5, open_sec=30, probes=3)
    b.record(False); b.record(False)
    time.sleep(31 / SCALE)
    assert b.allow()
    b.record(False)
    assert b.state == "OPEN"
