import random
from pathlib import Path

random.seed(7)
Path("logs").mkdir(exist_ok=True)
for i in range(1, 6):
    lines = []
    for n in range(120):
        t = f"[2026-11-08T20:{5 + n // 12:02d}:{n % 12 * 5:02d}.{n:03d}+0800][info][gc]"
        if i == 4:
            break                                   
        if n % 2:
            lines.append(f"{t} GC({n}) Using 8 workers of 8 for evacuation")
        else:
            kind = "Young (Normal) (G1 Evacuation Pause)" if n % 10 else "Young (Concurrent Start) (G1 Humongous Allocation)"
            ms = random.lognormvariate(3.2, 0.5) if n != 60 else 240 + i * 4.05
            lines.append(f"{t} GC({n}) Pause {kind} 512M->128M(1024M) {ms:.3f}ms")
    Path(f"logs/order-0{i}.log").write_text("\n".join(lines) + ("\n" if lines else ""))