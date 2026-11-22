"""注意力和 KV 缓存：有缓存和没缓存差多少，前缀变一个字缓存就失效。

    python main.py nocache    # 逐 token 写 20 个：每圈都把全部前文重算（没有 KV 缓存）vs 只算新的那个
    python main.py prefix     # 同一段日志问两个问题；再把当前时间放在系统提示开头 / 末尾，看要重算多少

连本机 llama-server。只用标准库。
"""

import json
import os
import random
import sys
import time
from datetime import datetime
import urllib.request

SERVER = os.environ.get("LLAMA_URL", "http://127.0.0.1:8090")
LOG = """\
[2026-11-08T20:12:03.512+0800][info][gc] GC(1213) Pause Young (Normal) (G1 Evacuation Pause) 412M->188M(1024M) 236.871ms
[2026-11-08T20:12:43.207+0800][info][gc] GC(1214) Pause Full (G1 Compaction Pause) 1010M->702M(1024M) 1873.412ms
[2026-11-08T20:13:10.033+0800][info][gc] GC(1215) Pause Young (Normal) (G1 Evacuation Pause) 905M->711M(1024M) 198.204ms"""


def post(path: str, body: dict) -> dict:
    req = urllib.request.Request(SERVER + path, json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        return json.load(r)


def chat(system: str, user: str) -> str:
    return (f"<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n"
            f"<|im_start|>assistant\n")


def tokenize(text: str) -> list[int]:
    return post("/tokenize", {"content": text, "parse_special": True})["tokens"]


def step(ids: list[int], cache: bool) -> tuple[int, dict]:
    r = post("/completion", {"prompt": ids, "n_predict": 1, "temperature": 0,
                             "cache_prompt": cache, "return_tokens": True})
    return r["tokens"][0], r["timings"]


def cmd_nocache() -> None:
    prompt = tokenize(chat("只根据日志回答，日志里没有的不要编。",
                           LOG + "\n\norder-04 在 20:15 变慢，是不是 GC 导致的？用一句话回答，并说出依据。"))
    for cache in (True, False):
        ids, total_n, t0 = list(prompt), 0, time.time()
        step(ids, True)  # 预热：两种做法都从“输入已经读过一遍”开始比
        t0 = time.time()
        for _ in range(20):
            tid, t = step(ids, cache)
            total_n += t["prompt_n"]
            ids.append(tid)
        dt = time.time() - t0
        name = "有 KV 缓存（只算新 token）" if cache else "没有 KV 缓存（每圈全部重算）"
        print(f"{name}：写 20 个 token 用 {dt:6.1f} s，一共算了 {total_n:5d} 个 token 位置，"
              f"平均每个 {dt / 20 * 1000:6.0f} ms")


def cmd_prefix() -> None:
    sys_prompt = "只根据日志回答，日志里没有的不要编。"
    q1, q2 = "最长的一次停顿是多少毫秒？", "有没有 Full GC？"

    def ask(system: str, q: str) -> dict:
        r = post("/completion", {"prompt": chat(system, LOG + "\n\n" + q), "n_predict": 1,
                                 "temperature": 0, "cache_prompt": True})
        return r["timings"]

    print("A. 同一段日志，换一个问题（问题在最后）：")
    fresh = f"[会话 {random.randint(1000, 9999)}] " + sys_prompt   # 开头加个随机号，保证第 1 次是冷的
    a1, a2 = ask(fresh, q1), ask(fresh, q2)
    print(f"   第 1 次：新算 {a1['prompt_n']:>3} 个，复用 {a1.get('cache_n', 0):>3} 个，{a1['prompt_ms']:6.0f} ms")
    print(f"   第 2 次：新算 {a2['prompt_n']:>3} 个，复用 {a2.get('cache_n', 0):>3} 个，{a2['prompt_ms']:6.0f} ms")

    print("B. 把当前时间放在系统提示开头：")
    for _ in range(2):
        now = datetime.now().strftime("%H:%M:%S.%f")[:-3]     # 真实的当前时间，每次都不同
        t = ask(f"现在是 {now}。" + sys_prompt, q1)
        print(f"   {now}：新算 {t['prompt_n']:>3} 个，复用 {t.get('cache_n', 0):>3} 个，{t['prompt_ms']:6.0f} ms")

    print("C. 同样的时间，放在问题后面（前缀不变）：")
    for _ in range(2):
        now = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        t = ask(sys_prompt, q1 + f"（现在是 {now}）")
        print(f"   {now}：新算 {t['prompt_n']:>3} 个，复用 {t.get('cache_n', 0):>3} 个，{t['prompt_ms']:6.0f} ms")


if __name__ == "__main__":
    {"nocache": cmd_nocache, "prefix": cmd_prefix}[sys.argv[1] if len(sys.argv) > 1 else "prefix"]()
