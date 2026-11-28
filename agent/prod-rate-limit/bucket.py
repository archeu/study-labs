"""自己这边的令牌桶：按 token 限，按租户分，先预扣再多退少补。

- 每个租户一个桶，速率按权重分（交易 5 : 支付 3 : 搜索 2），合起来是供应商额度的九成。
- 桶容量只放 10 秒的量：供应商也可能按 10 秒一段来卡，自己的桶不能比它更能“攒”。
- 单个请求比桶还大（搜索团队带 4 万 token 日志的那一轮）：等桶满了就放行，余额变成负数，
  后面的请求替它还债。这样大请求不会永远等不到，也不会把额度打穿。
"""
import asyncio
import os
import time

SCALE = float(os.environ.get("SCALE", "30"))
_t0 = time.monotonic()


def now() -> float:
    return (time.monotonic() - _t0) * SCALE


async def sleep(scenario_sec: float) -> None:
    await asyncio.sleep(max(0.0, scenario_sec) / SCALE)


class TokenBucket:
    def __init__(self, per_min: float, window: float = 10.0):
        self.rate = per_min / 60.0
        self.cap = self.rate * window
        self.tokens, self.t = self.cap, now()
        self.lock = asyncio.Lock()          # 先来先得：排队的请求按到达顺序拿
        self.waited = 0.0                   # 累计等了多少场景秒，给报告用

    def _refill(self) -> None:
        t = now()
        self.tokens = min(self.cap, self.tokens + (t - self.t) * self.rate)
        self.t = t

    async def acquire(self, n: float) -> None:
        start = now()
        async with self.lock:
            need = min(n, self.cap)          # 比桶还大的请求：等到桶满就放行
            while True:
                self._refill()
                if self.tokens >= need:
                    self.tokens -= n         # 可以扣成负数：欠的账后面还
                    break
                await sleep((need - self.tokens) / self.rate)
        self.waited += now() - start

    def settle(self, estimated: float, actual: float) -> None:
        """调用回来以后按 usage 里的真实数字多退少补。"""
        self.tokens -= actual - estimated


class TenantBuckets:
    def __init__(self, total_per_min: float, weights: dict[str, float]):
        s = sum(weights.values())
        self.b = {t: TokenBucket(total_per_min * w / s) for t, w in weights.items()}

    def __getitem__(self, tenant: str) -> TokenBucket:
        return self.b[tenant]


class FairBucket:
    """一个全局桶 + 加权公平排队：额度紧的时候按权重分，谁闲着就把份额让给别人。

    排队的请求先按优先级（P0 最先），同一优先级里挑“用量 ÷ 权重”最小的租户。
    只有一个租户在排队时，它能用满整个桶：份额是保底，不是天花板。
    """

    def __init__(self, total_per_min: float, weights: dict[str, float], window: float = 10.0):
        self.bucket = TokenBucket(total_per_min, window)
        self.w = weights
        self.used = {t: 0.0 for t in weights}
        self.waiting: list[tuple[int, float, str, float, asyncio.Future]] = []
        self.kick = asyncio.Event()
        self.task = asyncio.get_running_loop().create_task(self._grant())

    async def acquire(self, tenant: str, n: float, prio: int = 1) -> None:
        fut = asyncio.get_running_loop().create_future()
        self.waiting.append((prio, now(), tenant, n, fut))
        self.kick.set()
        await fut

    def settle(self, tenant: str, estimated: float, actual: float) -> None:
        self.bucket.settle(estimated, actual)
        self.used[tenant] += actual - estimated

    async def _grant(self) -> None:
        b = self.bucket
        while True:
            if not self.waiting:
                self.kick.clear()
                await self.kick.wait()
                continue
            self.waiting.sort(key=lambda x: (x[0], self.used[x[2]] / self.w[x[2]], x[1]))
            prio, _, tenant, n, fut = self.waiting[0]
            need = min(n, b.cap)
            b._refill()
            if b.tokens < need:
                await sleep((need - b.tokens) / b.rate)
                continue
            b.tokens -= n
            self.used[tenant] += n
            self.waiting.pop(0)
            fut.set_result(None)
