import asyncio, functools, time

print("== 1. 函数是值 ==")
def parse(text: str) -> int:
    return text.count("Pause")
handlers = {"parse": parse}                  # 放进字典
f = handlers["parse"]                        # 拿出来
print(f is parse, f.__name__, f("Pause Pause"))

print("== 2. 闭包 ==")
def over(limit):
    def check(ms):
        return ms > limit                    # 用到外层的 limit
    return check
slow = over(200)                             # over 已经返回，limit 还活着
print(slow(244.05), slow(31.6), slow.__closure__[0].cell_contents)

print("== 3. 登记型装饰器：import 时就执行 ==")
routes = {}
def get(path):
    print(f"  get({path!r}) 执行")
    def deco(fn):
        print(f"  deco({fn.__name__}) 执行，登记进路由表")
        routes[path] = fn
        return fn                            # 原样返回
    return deco

print("== 4. 包装型装饰器 ==")
def timed(fn):
    @functools.wraps(fn)                     # 抄名字、文档、签名
    async def wrapper(*args, **kwargs):
        t = time.perf_counter()
        try:
            return await fn(*args, **kwargs)
        finally:
            print(f"  {fn.__name__} 用了 {(time.perf_counter() - t) * 1000:.0f} ms")
    return wrapper

@get("/gc/summary")                          # ② 再登记：登记的是 wrapper
@timed                                       # ① 先包
async def gc_summary(service: str, minutes: int = 10):
    await asyncio.sleep(0.1)
    return {"service": service, "minutes": minutes}

print("routes:", list(routes), "登记的是:", routes["/gc/summary"].__name__,
      "原函数:", routes["/gc/summary"].__wrapped__.__name__)
print("== 5. 按路由表调用 ==")
print(asyncio.run(routes["/gc/summary"]("order")))