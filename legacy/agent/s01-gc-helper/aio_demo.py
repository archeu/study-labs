import asyncio, time

DELAYS = {"order-01": .12, "order-02": .08, "order-03": .15, "order-04": None, "order-05": .09}

async def fetch(host):                          # 假装拉日志：order-04 永远不回
    d = DELAYS[host]
    await asyncio.sleep(d if d is not None else 3600)
    return f"{host} ok"

async def fetch_t(host):                        # 全景第 7 站：1 秒超时
    try:
        async with asyncio.timeout(1.0):
            return await fetch(host)
    except TimeoutError:
        return f"{host} timeout"

def ms(t): return round((time.perf_counter() - t) * 1000)

async def lag_probe(stop, out):                 # 每 10 ms 醒一次，记下最晚迟到了多久
    worst = 0
    while not stop.is_set():
        t = time.perf_counter(); await asyncio.sleep(0.01)
        worst = max(worst, (time.perf_counter() - t - 0.01) * 1000)
    out.append(round(worst))

async def main():
    print("== 1. 一台一台等 vs 同时等 ==")
    t = time.perf_counter(); [await fetch_t(h) for h in DELAYS]; print("  逐个 await:", ms(t), "ms")
    t = time.perf_counter(); r = await asyncio.gather(*(fetch_t(h) for h in DELAYS))
    print("  gather    :", ms(t), "ms", r)

    print("== 2. 谁会卡住事件循环 ==")
    for name in ("await asyncio.sleep(1)", "time.sleep(1)", "await asyncio.to_thread(time.sleep, 1)"):
        stop, out = asyncio.Event(), []
        probe = asyncio.create_task(lag_probe(stop, out)); await asyncio.sleep(0.05)
        if name.startswith("await asyncio.sleep"): await asyncio.sleep(1)
        elif name == "time.sleep(1)": time.sleep(1)
        else: await asyncio.to_thread(time.sleep, 1)
        stop.set(); await probe
        print(f"  {name:40s} 事件循环最多迟到 {out[0]} ms")

    print("== 3. 一个出错，其他怎样 ==")
    async def boom(): await asyncio.sleep(.05); raise ValueError("max() arg is an empty sequence")
    async def slow(name):
        try:
            await asyncio.sleep(.2); print("    ", name, "跑完了"); return name
        except asyncio.CancelledError:
            print("    ", name, "被取消"); raise
    try: await asyncio.gather(slow("a"), boom(), slow("b"))
    except ValueError as e: print("  gather 抛出:", e)
    await asyncio.sleep(.3)
    try:
        async with asyncio.TaskGroup() as tg:
            tg.create_task(slow("a")); tg.create_task(boom()); tg.create_task(slow("b"))
    except* ValueError as eg: print("  TaskGroup 抛出:", eg.exceptions)

asyncio.run(main())
