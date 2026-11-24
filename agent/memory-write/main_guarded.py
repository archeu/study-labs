"""第二遍：同样的会话，换成“代码定边界、模型在边界里选”。    uv run python memory-write/main_guarded.py"""

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "memory-types"))
from memories import MEMORIES  # noqa: E402

from session import NOW, TRANSCRIPT  # noqa: E402
from write import EXTRACT_SCHEMA, EXTRACT_SYSTEM_V2, chat_json, decide, route  # noqa: E402


def main():
    t = time.perf_counter()
    cands = chat_json(EXTRACT_SYSTEM_V2, f"现在是 {NOW}。会话记录：\n{TRANSCRIPT}", EXTRACT_SCHEMA)["items"]
    print(f"== 1 抽取（加了写法要求和例子）：{len(cands)} 条（{time.perf_counter() - t:.0f}s）")
    for i, c in enumerate(cands, 1):
        print(f"  C{i} {c['kind']:<10} {c['source']:<5} key={c['key']}  exp={c['expires']}\n     {c['text']}")
    print("\n== 2+3 代码路由，必要时才问模型")
    for i, c in enumerate(cands, 1):
        op, target, note, near = route(c, MEMORIES)
        if op == "ASK":
            d = decide(c, near)
            ok = d["target"] in [m.id for _, m in near] or d["op"] == "ADD"
            op, target, note = (d["op"], d["target"], d["reason"]) if ok else ("ADD", None, "模型选的目标不在候选里，按新增处理")
        nn = "；".join(f"{m.id} {s:.3f}" for s, m in near)
        print(f"  C{i} {op:<7} target={target}  {note}" + (f"  [邻居 {nn}]" if nn else ""))
    print(f"\n总耗时 {time.perf_counter() - t:.0f}s")


if __name__ == "__main__":
    main()
