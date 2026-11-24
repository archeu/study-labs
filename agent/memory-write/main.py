"""⑤ 套进场景：第 8 站，把今天这次会话写进记忆表。

    llama-server -m Qwen3-1.7B-Q4_K_M.gguf --jinja -np 1 -c 8192 --port 8090
    llama-server -m Qwen3-Embedding-0.6B-Q8_0.gguf --embeddings --pooling last --port 8091
    uv run python memory-write/main.py
"""

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "memory-types"))
from memories import MEMORIES  # noqa: E402  交易组已有的 12 条

from session import NOW, TRANSCRIPT  # noqa: E402
from write import decide, extract, neighbors  # noqa: E402

# 硬规则：模型的决定之上再加一道代码检查
GUARDS = {
    "procedural": "程序性记忆只登记为“待确认”，值班人员确认后才生效",
    "model": "来源是模型推断的，不写入",
}


def main():
    t = time.perf_counter()
    cands = extract(TRANSCRIPT, NOW)
    print(f"== 1 抽取：{len(cands)} 条候选（{time.perf_counter() - t:.0f}s）")
    for i, c in enumerate(cands, 1):
        print(f"  C{i} {c['kind']:<10} {c['source']:<5} key={c['key']}  exp={c['expires']}\n     {c['text']}")

    print("\n== 2 去重：每条候选最像的两条旧记忆（余弦相似度）")
    near = neighbors(cands, MEMORIES)
    for i, n in enumerate(near, 1):
        print(f"  C{i} → " + "；".join(f"{m.id} {s:.3f}" for s, m in n))

    print("\n== 3 决定")
    added = []
    for i, (c, n) in enumerate(zip(cands, near), 1):
        if c["source"] == "model":
            print(f"  C{i} 跳过：{GUARDS['model']}")
            continue
        d = decide(c, n, added)
        guard = f"（{GUARDS['procedural']}）" if c["kind"] == "procedural" else ""
        print(f"  C{i} {d['op']:<6} target={d['target']}  {d['reason']}{guard}\n     → {d['text']}")
        if d["op"] == "ADD":
            added.append(d["text"])
    print(f"\n总耗时 {time.perf_counter() - t:.0f}s")


if __name__ == "__main__":
    main()
