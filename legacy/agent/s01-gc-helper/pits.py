g = [[0] * 3] * 3; g[0][0] = 9; print("grid", g)
g2 = [[0] * 3 for _ in range(3)]; g2[0][0] = 9; print("grid2", g2)
ms = [12.0, 256.2, 33.1, 80.4, 41.0]
top = ms[1:3]; top[0] = 0; print("slice copy", ms, top)
print(ms[-1], ms[-2:], ms[::-1], ms[::2], ms[10:], ms[1:100])
gen = (x for x in ms if x > 40); print("gen1", list(gen), "gen2", list(gen))
d = {"order-01": 1, "order-02": 0}
try:
    for k in d:
        if d[k] == 0: del d[k]
except RuntimeError as e: print("RT", e)
try: d["order-09"]
except KeyError as e: print("KeyError", e)
print(d.get("order-09"), d.get("order-09", 0))
a = 256; b = 256; c = 257; e = int("257"); print(a is b, c is e, c == e)
try: {[1]: 1}
except TypeError as e: print("TypeError", e)
x = 10; [x for x in range(3)]; print("x after comp", x)
print({**{"a":1}, "b":2} | {"a": 3})
print(sorted(["order-10","order-2","order-1"]), sorted(["order-10","order-2"], key=lambda h:int(h.split("-")[1])))
