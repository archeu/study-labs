import asyncio, time, threading, tracemalloc, sys, warnings, gc
DELAYS = {"order-01": .12, "order-02": .08, "order-03": .15, "order-04": None, "order-05": .09}
async def fetch(host):
    d = DELAYS[host]
    await asyncio.sleep(d if d is not None else 3600)
    return f"{host} ok"
async def fetch_t(host):
    try:
        async with asyncio.timeout(1.0):
            return await fetch(host)
    except TimeoutError:
        return f"{host} timeout"
def cpu(n=6_000_000):
    s = 0
    for i in range(n): s += i
    return s
def ms(t): return round((time.perf_counter() - t) * 1000)

async def lag_probe(stop, out):
    worst = 0
    while not stop.is_set():
        t = time.perf_counter(); await asyncio.sleep(0.01)
        worst = max(worst, (time.perf_counter() - t - 0.01) * 1000)
    out.append(round(worst))

async def main():
    # 1 serial vs gather
    t = time.perf_counter(); r = [await fetch_t(h) for h in DELAYS]; print("1 serial", ms(t), r)
    t = time.perf_counter(); r = await asyncio.gather(*(fetch_t(h) for h in DELAYS)); print("1 gather", ms(t), r)
    # 2 blocking vs non-blocking, measured by loop lag
    for name in ("asyncio.sleep", "time.sleep", "to_thread(time.sleep)"):
        stop, out = asyncio.Event(), []
        probe = asyncio.create_task(lag_probe(stop, out)); await asyncio.sleep(0.05)
        t = time.perf_counter()
        if name == "asyncio.sleep": await asyncio.sleep(1)
        elif name == "time.sleep": time.sleep(1)
        else: await asyncio.to_thread(time.sleep, 1)
        stop.set(); await probe
        print("2", name, "took", ms(t), "worst loop lag", out[0], "ms")
    # 3 cpu bound: inline vs to_thread vs process pool on 2 jobs
    t = time.perf_counter(); cpu(); one = ms(t); print("3 one cpu job", one)
    t = time.perf_counter(); await asyncio.gather(asyncio.to_thread(cpu), asyncio.to_thread(cpu)); print("3 two jobs to_thread", ms(t))
    from concurrent.futures import ProcessPoolExecutor
    loop = asyncio.get_running_loop()
    with ProcessPoolExecutor(2) as pool:
        await loop.run_in_executor(pool, cpu, 10)
        t = time.perf_counter(); await asyncio.gather(loop.run_in_executor(pool, cpu), loop.run_in_executor(pool, cpu)); print("3 two jobs processpool", ms(t))
    # 4 exceptions
    async def boom(): await asyncio.sleep(.05); raise ValueError("max() arg is an empty sequence")
    async def slow(name):
        try: await asyncio.sleep(.2); return name
        except asyncio.CancelledError: print("   ", name, "cancelled"); raise
    t = time.perf_counter()
    try: await asyncio.gather(slow("a"), boom(), slow("b"))
    except ValueError as e: print("4 gather raised after", ms(t), "ms:", e)
    await asyncio.sleep(.3)
    print("4 return_exceptions", await asyncio.gather(slow("a"), boom(), return_exceptions=True))
    t = time.perf_counter()
    try:
        async with asyncio.TaskGroup() as tg:
            tg.create_task(slow("a")); tg.create_task(boom()); tg.create_task(slow("b"))
    except* ValueError as eg: print("4 TaskGroup raised after", ms(t), "ms:", repr(eg.exceptions))
    # 5 forgot await
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("always")
        x = fetch("order-02"); print("5 forgot await ->", type(x).__name__); del x; gc.collect()
        print("5 warning:", [str(i.message) for i in w][:1])
    # 6 10k concurrent: tasks vs threads
    tracemalloc.start(); t = time.perf_counter()
    await asyncio.gather(*(asyncio.sleep(.5) for _ in range(10000)))
    print("6 10k coroutines", ms(t), "ms peak", round(tracemalloc.get_traced_memory()[1]/1e6,1), "MB"); tracemalloc.stop()
    def th(): time.sleep(.5)
    t = time.perf_counter(); ts = [threading.Thread(target=th) for _ in range(2000)]
    [x.start() for x in ts]; [x.join() for x in ts]; print("6 2000 threads", ms(t), "ms")
    # 7 semaphore
    sem = asyncio.Semaphore(10); live = 0; peak = 0
    async def lim():
        nonlocal live, peak
        async with sem:
            live += 1; peak = max(peak, live); await asyncio.sleep(.05); live -= 1
    t = time.perf_counter(); await asyncio.gather(*(lim() for _ in range(250))); print("7 250 with sem10", ms(t), "peak", peak)
    print("threads now", threading.active_count(), sys.version.split()[0], "gil", getattr(sys, "_is_gil_enabled", lambda: True)())
if __name__ == "__main__":
    asyncio.run(main())
