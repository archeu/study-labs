"""⑤ 亲眼看到：三种记忆混在一起只按相似度取，和按类型分开取，取回来的有什么不同。

需要一个 embedding 服务（本机 llama-server + Qwen3-Embedding-0.6B，OpenAI 兼容的 /v1/embeddings）：
    llama-server -m Qwen3-Embedding-0.6B-Q8_0.gguf --embeddings --pooling last --port 8091
    EMBED_URL=http://127.0.0.1:8091/v1/embeddings uv run python memory-types/main.py
"""

import json
import math
import os
import urllib.request
from datetime import date

from memories import MEMORIES, QUESTION, SERVICE

URL = os.environ.get("EMBED_URL", "http://127.0.0.1:8091/v1/embeddings")
TODAY = date(2026, 11, 9)
# Qwen3-Embedding 的用法：查询前面加一句任务说明，文档不加
QUERY_PREFIX = "Instruct: 给定一个值班告警问题，找出对排查有用的记忆\nQuery: "


def embed(texts):
    req = urllib.request.Request(URL, json.dumps({"input": texts}).encode(), {"content-type": "application/json"})
    data = json.load(urllib.request.urlopen(req))["data"]
    return [d["embedding"] for d in sorted(data, key=lambda d: d["index"])]


def cos(a, b):
    return sum(x * y for x, y in zip(a, b)) / math.sqrt(sum(x * x for x in a) * sum(y * y for y in b))


def main():
    q = embed([QUERY_PREFIX + QUESTION])[0]
    vecs = embed([m.text for m in MEMORIES])
    sims = {m.id: cos(q, v) for m, v in zip(MEMORIES, vecs)}

    print("== 一锅端：12 条混在一起，只按相似度排，取前 3")
    ranked = sorted(MEMORIES, key=lambda m: -sims[m.id])
    for i, m in enumerate(ranked, 1):
        mark = "→" if i <= 3 else " "
        print(f"{mark} {i:2d}. {m.id} {m.kind:<10} {sims[m.id]:.3f}  {m.text[:34]}")

    print("\n== 按类型分开取")
    proc = [m for m in MEMORIES if m.kind == "procedural" and m.key in ("*", SERVICE)]
    sem = [m for m in MEMORIES if m.kind == "semantic" and m.key.startswith(SERVICE + "/")]

    def episodic_score(m):  # 相似度为主，越新越好：每过 30 天打 9 折
        age = (TODAY - date.fromisoformat(m.date)).days
        return sims[m.id] * 0.9 ** (age / 30)
    epi = sorted((m for m in MEMORIES if m.kind == "episodic"), key=episodic_score, reverse=True)[:2]
    print("程序性（这个范围的全部加载，不比相似度）：", [m.id for m in proc])
    print("语义（按 key 精确取 order-svc/*）：     ", [m.id for m in sem])
    print("情景（相似度 × 新近，取前 2）：         ", [(m.id, round(episodic_score(m), 3)) for m in epi])
    p1 = next(i for i, m in enumerate(ranked, 1) if m.id == "P1")
    print(f"\nP1“晚高峰不 dump”在一锅端里排第 {p1}，按类型取时一定在。")


if __name__ == "__main__":
    main()
