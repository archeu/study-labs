"""采样：temperature 和 top_p 改的是什么。

    python main.py probs     # 回答第一个 token 的前 20 名候选和概率（温度作用之前）
    python main.py runs      # 温度 0 和温度 1 各跑 10 次，数有几种不同的回答
    python main.py batch     # 温度 0：一个一个发 vs 4 个同时发，结果一样吗

连本机 llama-server（4 个并发槽）。只用标准库。
"""

import collections
import concurrent.futures as cf
import json
import math
import os
import sys
import urllib.request

SERVER = os.environ.get("LLAMA_URL", "http://127.0.0.1:8090")
SYSTEM = "只根据日志回答，日志里没有的不要编。"
LOG = """\
[2026-11-08T20:12:03.512+0800][info][gc] GC(1213) Pause Young (Normal) (G1 Evacuation Pause) 412M->188M(1024M) 236.871ms
[2026-11-08T20:12:43.207+0800][info][gc] GC(1214) Pause Full (G1 Compaction Pause) 1010M->702M(1024M) 1873.412ms
[2026-11-08T20:13:10.033+0800][info][gc] GC(1215) Pause Young (Normal) (G1 Evacuation Pause) 905M->711M(1024M) 198.204ms"""
QUESTION = "order-04 在 20:15 变慢，是不是 GC 导致的？用一句话回答，并说出依据。"
PROMPT = (f"<|im_start|>system\n{SYSTEM}<|im_end|>\n<|im_start|>user\n{LOG}\n\n{QUESTION}<|im_end|>\n"
          f"<|im_start|>assistant\n")


def post(path: str, body: dict) -> dict:
    req = urllib.request.Request(SERVER + path, json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.load(r)


def gen(temperature: float, seed: int, cache: bool = True, n: int = 48) -> str:
    # 只开 temperature 这一个采样器（再加 top_k=0、top_p=1、min_p=0 把 llama.cpp 默认的截断关掉）
    r = post("/completion", {"prompt": PROMPT, "n_predict": n, "temperature": temperature, "seed": seed,
                             "top_k": 0, "top_p": 1.0, "min_p": 0.0, "cache_prompt": cache})
    return r["content"].strip()


def cmd_probs() -> None:
    r = post("/completion", {"prompt": PROMPT, "n_predict": 1, "n_probs": 20, "temperature": 0})
    top = r["completion_probabilities"][0]["top_logprobs"]
    print("第一个 token 的前 20 名（温度作用之前的原始概率）：")
    for c in top:
        print(f"   {c['token']!r:<12} {math.exp(c['logprob']):.4f}")
    print(f"   前 20 名合计 {sum(math.exp(c['logprob']) for c in top):.4f}")


def cmd_runs() -> None:
    for t in (0.0, 1.0):
        outs = [gen(t, seed) for seed in range(10)]
        cnt = collections.Counter(outs)
        first = collections.Counter(o[:1] if not o.startswith("不是") else "不是" for o in outs)
        print(f"\n温度 {t}：10 次里有 {len(cnt)} 种不同的回答；开头字分布 {dict(first)}")
        for text, k in cnt.most_common():
            print(f"   ×{k} {text[:70]}")


def cmd_batch() -> None:
    solo = [gen(0.0, 0, cache=False) for _ in range(2)]
    with cf.ThreadPoolExecutor(4) as ex:
        together = list(ex.map(lambda i: gen(0.0, i, cache=False), range(4)))
    print("一个一个发（温度 0）：")
    for s in solo:
        print("   ", s[:80])
    print("4 个同时发（温度 0）：")
    for s in together:
        print("   ", s[:80], "" if s == solo[0] else "   ← 和单独发的不一样")


if __name__ == "__main__":
    {"probs": cmd_probs, "runs": cmd_runs, "batch": cmd_batch}[sys.argv[1] if len(sys.argv) > 1 else "runs"]()
