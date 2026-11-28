"""令牌桶的两条性质：大请求能借债放行；只有一个租户排队时能用满全局桶。"""
import asyncio
import os

os.environ.setdefault("SCALE", "600")
from bucket import FairBucket, TokenBucket, now  # noqa: E402


def test_big_request_goes_into_debt():
    async def go():
        b = TokenBucket(60_000)            # 每秒 1000，容量 10 秒 = 1 万
        await b.acquire(40_000)            # 比桶大：桶满就放行
        assert b.tokens < 0                # 欠了 3 万
    asyncio.run(go())


def test_single_tenant_can_use_whole_bucket():
    async def go():
        fb = FairBucket(60_000, {"trade": 5, "search": 2})
        t = now()
        for _ in range(4):                 # 4 × 5000 = 2 万：桶里 1 万 + 再补 10 秒
            await fb.acquire("search", 5000)
        fb.task.cancel()
        return now() - t
    took = asyncio.run(go())
    assert 8 <= took <= 14                 # 按全局速率等，而不是按搜索那 2/7 的份额（要 35 秒）
