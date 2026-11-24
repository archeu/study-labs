"""⑤ 跟着做一遍：20 个用例，跑 v1、v2 和两次“改坏了”的回归。

    llama-server -m Qwen3-Embedding-0.6B-Q8_0.gguf --embeddings --pooling last --port 8091
    EMBED_URL=http://127.0.0.1:8091/v1/embeddings uv run python memory-eval/main.py
另把 8 个开关的 256 种组合各跑一遍，结果写进 grid.json（页面实验台查的就是它）。
"""

import itertools
import json
import os
import urllib.request
from pathlib import Path

from cases import BG, CASE_21, CASES, D
from system import SWITCHES, Store, check, cos

URL = os.environ.get("EMBED_URL", "http://127.0.0.1:8091/v1/embeddings")
PREFIX = "Instruct: 给定一个值班问题，找出对排查有用的记忆\nQuery: "
_cache = {}


def embed(texts):
    todo = [t for t in texts if t not in _cache]
    for i in range(0, len(todo), 16):
        batch = todo[i:i + 16]
        req = urllib.request.Request(URL, json.dumps({"input": batch}).encode(), {"content-type": "application/json"})
        for d in sorted(json.load(urllib.request.urlopen(req))["data"], key=lambda d: d["index"]):
            _cache[batch[d["index"]]] = d["embedding"]
    return [_cache[t] for t in texts]


def run(cfg, verbose=False, cases=CASES):
    results = {}
    for case in cases:
        st = Store(cfg)
        for ev in BG + case["events"]:
            st.apply(ev)
        qv = embed([PREFIX + case["q"]])[0]
        texts = st.recall(case["q"], "trade", case.get("at", D), lambda t: cos(qv, embed([t])[0]))
        fails = check(case, texts)
        results[case["id"]] = fails
        if verbose and fails:
            print(f"   ✗ #{case['id']:<2} {case['cat']} {case['q'][:22]}…  {'；'.join(fails)}")
    return results


def summary(results, cases=CASES):
    cats = {}
    for case in cases:
        ok = not results[case["id"]]
        p, n = cats.get(case["cat"], (0, 0))
        cats[case["cat"]] = (p + ok, n + 1)
    total = sum(p for p, _ in cats.values())
    return total, cats


ALL_ON = {s: True for s in SWITCHES}
CONFIGS = {
    "v1：全追加 + 相似度前 3": {s: False for s in SWITCHES},
    "v2：本站规则全开": ALL_ON,
    "v2 改坏①：去掉新近项": dict(ALL_ON, recency=False),
    "v2 改坏②：新接口漏了团队条件": dict(ALL_ON, team=False),
}


def main():
    for name, cfg in CONFIGS.items():
        print(f"== {name}")
        total, cats = summary(run(cfg, verbose=True))
        print(f"   通过 {total}/20  " + "  ".join(f"{k} {p}/{n}" for k, (p, n) in cats.items()))
    print("\n== 补上第 21 个用例（专门抓“去掉新近项”）后再跑")
    all21 = CASES + [CASE_21]
    for name, cfg in CONFIGS.items():
        total, _ = summary(run(cfg, cases=all21), all21)
        print(f"   {name:<20} 通过 {total}/21")
    grid = {}
    for bits in itertools.product([0, 1], repeat=len(SWITCHES)):
        res = run(dict(zip(SWITCHES, map(bool, bits))), cases=all21)
        grid["".join(map(str, bits))] = [c["id"] for c in all21 if res[c["id"]]]   # 记下挂掉的用例
    Path(__file__).with_name("grid.json").write_text(json.dumps(grid))
    print(f"\n256 种组合已写入 grid.json；嵌入调用缓存了 {len(_cache)} 段文本")


if __name__ == "__main__":
    main()
