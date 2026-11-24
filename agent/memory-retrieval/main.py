"""⑤ 亲眼看到：同样 25 条情景记忆，四种打分各取回哪 3 条。

    llama-server -m Qwen3-Embedding-0.6B-Q8_0.gguf --embeddings --pooling last --port 8091
    EMBED_URL=http://127.0.0.1:8091/v1/embeddings uv run python memory-retrieval/main.py
结果另存一份 scores.json（页面实验台用的就是这份相似度）。
"""

import json
import math
import os
import urllib.request
from datetime import date
from pathlib import Path

from episodes import EPISODES, QUESTION, TEAM, TODAY

URL = os.environ.get("EMBED_URL", "http://127.0.0.1:8091/v1/embeddings")
QUERY_PREFIX = "Instruct: 给定一个值班告警问题，找出对排查有用的过往经历\nQuery: "
HALF_LIFE_DAYS = 30


def embed(texts):
    req = urllib.request.Request(URL, json.dumps({"input": texts}).encode(), {"content-type": "application/json"})
    data = json.load(urllib.request.urlopen(req))["data"]
    return [d["embedding"] for d in sorted(data, key=lambda d: d["index"])]


def cos(a, b):
    return sum(x * y for x, y in zip(a, b)) / math.sqrt(sum(x * x for x in a) * sum(y * y for y in b))


def minmax(xs):
    lo, hi = min(xs), max(xs)
    return [(x - lo) / (hi - lo) if hi > lo else 0.0 for x in xs]


def score(rows, a=1.0, b=1.0, c=1.0, half_life=HALF_LIFE_DAYS, normalize=True):
    """Generative Agents 的做法：相关、新近、重要三项各自归一到 [0,1]，再加权求和。"""
    rel = [r["sim"] for r in rows]
    rec = [0.5 ** (r["age"] / half_life) for r in rows]           # 半衰期：过 half_life 天，新近分减半
    imp = [r["importance"] / 10 for r in rows]
    if normalize:
        rel, rec, imp = minmax(rel), minmax(rec), minmax(imp)
    return [a * x + b * y + c * z for x, y, z in zip(rel, rec, imp)]


def top(rows, scores, k=3):
    return [rows[i]["id"] for i in sorted(range(len(rows)), key=lambda i: -scores[i])[:k]]


def main():
    mine = [e for e in EPISODES if e.team == TEAM]            # 第一步永远是过滤，不是打分
    print(f"全部 {len(EPISODES)} 条，team={TEAM} 过滤后 {len(mine)} 条")
    q = embed([QUERY_PREFIX + QUESTION])[0]
    vecs = embed([e.text for e in mine])
    today = date.fromisoformat(TODAY)
    rows = [{"id": e.id, "sim": round(cos(q, v), 4), "age": (today - date.fromisoformat(e.date)).days,
             "importance": e.importance, "text": e.text} for e, v in zip(mine, vecs)]

    schemes = {
        "只看相关": score(rows, 1, 0, 0),
        "只看新近": score(rows, 0, 1, 0),
        "只看重要": score(rows, 0, 0, 1),
        "三项归一后相加": score(rows, 1, 1, 1),
        "三项相加但不归一": score(rows, 1, 1, 1, normalize=False),
    }
    for name, s in schemes.items():
        print(f"{name:<12} 前 3：{top(rows, s)}")
    print("\n相似度从高到低：")
    for r in sorted(rows, key=lambda r: -r["sim"])[:8]:
        print(f"  {r['id']:<4} {r['sim']:.3f}  {r['age']:>3} 天前  重要 {r['importance']:>2}  {r['text'][:30]}")
    sims = [r["sim"] for r in rows]
    print(f"\n相似度范围 {min(sims):.3f}～{max(sims):.3f}；新近分范围（半衰期 {HALF_LIFE_DAYS} 天）"
          f"{0.5 ** (max(r['age'] for r in rows) / HALF_LIFE_DAYS):.3f}～{0.5 ** (min(r['age'] for r in rows) / HALF_LIFE_DAYS):.3f}")
    Path(__file__).with_name("scores.json").write_text(json.dumps(
        [{k: r[k] for k in ("id", "sim", "age", "importance")} for r in rows], ensure_ascii=False))


if __name__ == "__main__":
    main()
