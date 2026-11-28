"""熔断器和令牌桶（场景时间）。

CircuitBreaker 照 Resilience4j 的“按次数的滑动窗口”写：
- CLOSED：记最近 WINDOW 次调用的成败；至少 MIN_CALLS 次、失败率 ≥ THRESHOLD 就打开。
- OPEN：OPEN_SEC 秒内一律不放行，调用方直接走备用；时间到了进 HALF_OPEN。
- HALF_OPEN：只放 PROBES 个试探请求；全成功就关上，有一个失败就再打开。
只有“供应商那边坏了”才算失败：529、5xx、超时、连接错误。429 是自己的额度问题，不算（交给限流）；400 是请求本身错了，也不算。
"""
import asyncio
import collections
import os
import time

SCALE = float(os.environ.get("SCALE", "30"))
_t0 = time.monotonic()


def now() -> float:
    return (time.monotonic() - _t0) * SCALE


async def sleep(sec: float) -> None:
    await asyncio.sleep(max(0.0, sec) / SCALE)


class CircuitBreaker:
    def __init__(self, window=20, min_calls=10, threshold=0.5, open_sec=30.0, probes=3):
        self.window, self.min_calls, self.threshold, self.open_sec, self.probes = window, min_calls, threshold, open_sec, probes
        self.state, self.calls = "CLOSED", collections.deque(maxlen=window)
        self.opened_at, self.probe_sent, self.probe_ok = 0.0, 0, 0
        self.log: list[tuple[float, str]] = []          # (场景秒, 新状态)，画时间线用

    def _to(self, s: str) -> None:
        self.state = s
        self.log.append((now(), s))

    def allow(self) -> bool:
        if self.state == "OPEN" and now() - self.opened_at >= self.open_sec:
            self._to("HALF_OPEN"); self.probe_sent = self.probe_ok = 0
        if self.state == "CLOSED":
            return True
        if self.state == "HALF_OPEN" and self.probe_sent < self.probes:
            self.probe_sent += 1
            return True
        return False

    def record(self, ok: bool) -> None:
        if self.state == "HALF_OPEN":
            if not ok:
                self._to("OPEN"); self.opened_at = now()
            else:
                self.probe_ok += 1
                if self.probe_ok >= self.probes:
                    self._to("CLOSED"); self.calls.clear()
            return
        if self.state != "CLOSED":
            return
        self.calls.append(ok)
        fails = self.calls.count(False)
        if len(self.calls) >= self.min_calls and fails / len(self.calls) >= self.threshold:
            self._to("OPEN"); self.opened_at = now()


class TokenBucket:
    """按 token 的令牌桶（上一页的简化版）：容量 10 秒的量，大请求可以欠账。"""

    def __init__(self, per_min: float, window: float = 10.0):
        self.rate = per_min / 60.0; self.cap = self.rate * window
        self.tokens, self.t = self.cap, now()
        self.lock = asyncio.Lock()

    async def acquire(self, n: float) -> None:
        async with self.lock:
            need = min(n, self.cap)
            while True:
                t = now(); self.tokens = min(self.cap, self.tokens + (t - self.t) * self.rate); self.t = t
                if self.tokens >= need:
                    self.tokens -= n; return
                await sleep((need - self.tokens) / self.rate)
