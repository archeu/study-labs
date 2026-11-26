"""从标准输入读 G1 的 GC 日志（-Xlog:gc），输出一行摘要。脚本本身不进模型的上下文，只有这一行输出进去。

    python scripts/gc_summary.py < gc.log
"""

import re
import sys

rows = [r for r in sys.stdin.read().splitlines() if "Pause" in r]
pauses = [float(m.group(1)) for r in rows if (m := re.search(r"([\d.]+)ms$", r))]
fulls = [r for r in rows if "Pause Full" in r]
after = [re.search(r"->(\d+)M\((\d+)M\)", r) for r in fulls]
pct = [int(a.group(1)) / int(a.group(2)) * 100 for a in after if a]
print(f"停顿 {len(rows)} 次，最长 {max(pauses, default=0):.0f} ms；Full GC {len(fulls)} 次，"
      f"回收后堆占用 {', '.join(f'{p:.0f}%' for p in pct) or '—'}")
