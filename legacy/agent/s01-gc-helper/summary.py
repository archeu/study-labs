import re
from collections import Counter
from pathlib import Path

PAUSE = re.compile(r"Pause (\w+ \(.+?\)) .* (\d+\.\d+)ms$")
HOSTS = [f"order-0{i}" for i in range(1, 6)]                        # 列表推导式

def parse(text: str) -> dict:
    hits = [PAUSE.search(line) for line in text.splitlines()]
    pauses = [(m.group(1), float(m.group(2))) for m in hits if m]   # 过滤 + 变换，元素是 tuple
    ms = [p for _, p in pauses]                                      # 拆包，_ 表示不要
    return {                                                         # 字典字面量
        "pauses": len(ms),
        "max_ms": max(ms, default=None),                             # 空列表不炸
        "avg_ms": round(sum(ms) / len(ms), 1) if ms else None,       # 空列表当条件是假
        "top3": sorted(ms, reverse=True)[:3],                        # 切片取前 3
        "kinds": Counter(k for k, _ in pauses),                      # 生成器喂给 Counter
    }

stats = {h: parse(Path(f"logs/{h}.log").read_text()) for h in HOSTS}   # 字典推导式
for host, s in stats.items():                                        # 按插入顺序
    print(host, s["pauses"], s["max_ms"], s["avg_ms"], s["top3"], dict(s["kinds"]))

slow = [h for h, s in stats.items() if s["max_ms"] and s["max_ms"] > 200]
empty = [h for h, s in stats.items() if not s["pauses"]]
print("最长停顿超过 200 ms:", slow)
print("没拿到停顿:", empty)