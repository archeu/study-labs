import re, timeit, tracemalloc, sys
lines = open("logs/order-01.log").read().splitlines() * 5000   # 60 万行
PAUSE = re.compile(r"Pause .* (\d+\.\d+)ms$")
def loop():
    out = []
    for line in lines:
        m = PAUSE.search(line)
        if m:
            out.append(float(m.group(1)))
    return out
def comp():
    return [float(m.group(1)) for line in lines if (m := PAUSE.search(line))]
nums = list(range(1_000_000))
def loop2():
    out = []
    for x in nums:
        out.append(x * 2)
    return out
def comp2():
    return [x * 2 for x in nums]
def map2():
    return list(map(lambda x: x * 2, nums))
for f in (loop, comp, loop2, comp2, map2):
    t = min(timeit.repeat(f, number=1, repeat=5))
    print(f"{f.__name__:6s} {t*1000:7.1f} ms")
# 内存：列表 vs 生成器，算平均
def peak(fn):
    tracemalloc.start(); r = fn(); p = tracemalloc.get_traced_memory()[1]; tracemalloc.stop(); return r, p
r1, p1 = peak(lambda: sum([float(m.group(1)) for line in lines if (m := PAUSE.search(line))]))
r2, p2 = peak(lambda: sum(float(m.group(1)) for line in lines if (m := PAUSE.search(line))))
print(f"list peak {p1/1e6:.1f} MB, gen peak {p2/1e3:.1f} KB, same={r1==r2}")
print("getsizeof list of 300k floats:", sys.getsizeof([0.0]*300000)/1e6, "MB (只算指针)")
# in list vs in set
hosts = [f"host-{i}" for i in range(10000)]; hs = set(hosts)
print("in list", min(timeit.repeat(lambda: "host-9999" in hosts, number=1000, repeat=5))*1e3, "us/次")
print("in set ", min(timeit.repeat(lambda: "host-9999" in hs, number=1000, repeat=5))*1e3, "us/次")
