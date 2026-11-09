import asyncio, time, dis
HOSTS = [f"order-0{i}" for i in range(1, 4)]
# 1 迟绑定
fs = [lambda: h for h in HOSTS]; print("late", [f() for f in fs])
fs2 = [lambda h=h: h for h in HOSTS]; print("fixed", [f() for f in fs2])
# 2 可变默认参数
def add(host, seen=[]):
    seen.append(host); return seen
print("default", add("order-01"), add("order-02"))
# 3 nonlocal
def counter():
    n = 0
    def inc():
        n += 1; return n
    return inc
try: counter()()
except UnboundLocalError as e: print("ULE", e)
def counter2():
    n = 0
    def inc():
        nonlocal n; n += 1; return n
    return inc
c = counter2(); c(); print("nonlocal", c(), c.__closure__[0].cell_contents)
# 4 闭包 cell
def over(limit):
    def check(ms): return ms > limit
    return check
slow = over(200)
print("closure", slow(244.05), slow.__code__.co_freevars, slow.__closure__[0].cell_contents)
dis.dis(slow)
# 5 sync wrapper on async
def timed_sync(fn):
    def wrapper(*a):
        t = time.perf_counter(); r = fn(*a); print(f"sync wrapper 量到 {(time.perf_counter()-t)*1000:.3f} ms"); return r
    return wrapper
@timed_sync
async def fetch(): await asyncio.sleep(0.1); return 1
t = time.perf_counter(); asyncio.run(fetch()); print(f"实际 {(time.perf_counter()-t)*1000:.0f} ms")
# 6 import 时就执行
print("--- import time ---")
routes = {}
def get(path):
    print(f"get({path!r}) 被调用"); 
    def deco(fn):
        print(f"deco({fn.__name__}) 被调用，登记"); routes[path] = fn; return fn
    return deco
@get("/gc/summary")
def gc_summary(): return "ok"
print("routes", routes, routes["/gc/summary"] is gc_summary)
# 7 内部调用也经过包装（对比 Spring self-invocation）
log = []
def traced(fn):
    def w(*a): log.append(fn.__name__); return fn(*a)
    return w
@traced
def parse(x): return x
@traced
def summary(): return parse(1)
summary(); print("traced", log)
