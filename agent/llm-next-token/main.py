"""一次只写一个 token：自己写生成循环。

每一圈只让 llama-server 算 1 个 token（n_predict=1），拿回前 5 个候选的概率，
挑概率最大的那个接到末尾，再算下一个，直到写出结束符 <|im_end|> 或到上限。

    python main.py                 # 手写循环，逐 token 打印候选和概率
    python main.py --force 不是    # 先替模型写好开头“不是”，看它怎么往下接
    python main.py --once          # 让服务端自己一口气写完，对比手写循环的结果

只用标准库。需要先起一个 llama-server（见 README）。
"""

import argparse
import json
import math
import os
import time
import urllib.request

SERVER = os.environ.get("LLAMA_URL", "http://127.0.0.1:8090")
END = "<|im_end|>"
MAX_NEW = 120

SYSTEM = "只根据日志回答，日志里没有的不要编。"
LOG = """\
[2026-11-08T20:12:03.512+0800][info][gc] GC(1213) Pause Young (Normal) (G1 Evacuation Pause) 412M->188M(1024M) 236.871ms
[2026-11-08T20:12:43.207+0800][info][gc] GC(1214) Pause Full (G1 Compaction Pause) 1010M->702M(1024M) 1873.412ms
[2026-11-08T20:13:10.033+0800][info][gc] GC(1215) Pause Young (Normal) (G1 Evacuation Pause) 905M->711M(1024M) 198.204ms"""
QUESTION = "order-04 在 20:15 变慢，是不是 GC 导致的？用一句话回答，并说出依据。"


def chat_prompt(system: str, user: str) -> str:
    """Qwen2.5 的对话模板：对模型来说，整段对话就是一段要续写的文字。"""
    return (f"<|im_start|>system\n{system}<|im_end|>\n"
            f"<|im_start|>user\n{user}<|im_end|>\n"
            f"<|im_start|>assistant\n")


def post(path: str, body: dict) -> dict:
    req = urllib.request.Request(SERVER + path, json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.load(r)


def tokenize(text: str) -> list[int]:
    return post("/tokenize", {"content": text, "parse_special": True})["tokens"]


def next_token(ids: list[int]) -> dict:
    """第 6 站：前文 -> 下一个 token 的概率表（只取前 5 名）。"""
    r = post("/completion", {
        "prompt": ids, "n_predict": 1, "n_probs": 5, "return_tokens": True,
        "temperature": 0, "cache_prompt": True,   # 温度 0：总挑概率最大的
    })
    t = r["timings"]
    probs = r.get("completion_probabilities")
    if not probs:
        # 挑中的 token 只是半个汉字（UTF-8 的前几个字节）：服务端要等字凑齐才往外吐，
        # 这一圈不返回概率，只给 token id
        return {"top": [(r["tokens"][0], "（半个字）", float("nan"))],
                "prompt_ms": t["prompt_ms"], "prompt_n": t["prompt_n"]}
    cand = probs[0]["top_logprobs"]
    return {"top": [(c["id"], c["token"], math.exp(c["logprob"])) for c in cand],
            "prompt_ms": t["prompt_ms"], "prompt_n": t["prompt_n"]}


def generate(prefix_ids: list[int], forced: str = "", ignore_end: bool = False,
             log: list | None = None) -> str:
    end_id = tokenize(END)[0]   # 结束符也是词表里的一个 token，这个模型是 151645
    ids = list(prefix_ids)
    if forced:  # 替模型写好开头：它没法“回头改”，只能顺着往下接
        ids += tokenize(forced)
        print(f"   （开头强制写成：{forced}）")
    for i in range(1, MAX_NEW + 1):
        t0 = time.perf_counter()
        nt = next_token(ids)
        ms = (time.perf_counter() - t0) * 1000
        tid, tok, p = nt["top"][0]                      # 第 7 站：温度 0，挑第一名
        alts = "  ".join(f"{t!r} {q:.3f}" for _, t, q in nt["top"][1:3])
        tok = "<|im_end|>" if tid == end_id else tok   # 服务端把特殊 token 显示成空串
        if log is not None:
            log.append({"n": i, "top": [["<|im_end|>" if c[0] == end_id else c[1], round(c[2], 3)]
                                        for c in nt["top"][:3]]})
        print(f"{i:>3} {tok!r:<14} p={p:.3f}   其次 {alts:<34} "
              f"重算 {nt['prompt_n']:>3} 个 token，{ms:6.0f} ms")
        if tid == end_id and not ignore_end:            # 第 9 站：写出结束符就停
            print("   → 挑中结束符 <|im_end|>，自己停下（stop_reason = end_turn）")
            break
        ids.append(tid)                                 # 第 8 站：接上，再来一圈
    else:
        print(f"   → 写满 {MAX_NEW} 个 token，被截断（stop_reason = max_tokens）")
    # 整段一起解码：单个 token 可能只是半个汉字，拼在一起才是完整的字
    return post("/detokenize", {"tokens": ids[len(prefix_ids):]})["content"]


def once(prefix_ids: list[int]) -> str:
    r = post("/completion", {"prompt": prefix_ids, "n_predict": MAX_NEW,
                             "temperature": 0, "cache_prompt": False})  # 不复用缓存，量一次冷启动
    t = r["timings"]
    print(f"   服务端一口气写了 {t['predicted_n']} 个 token：读输入 {t['prompt_n']} 个用 "
          f"{t['prompt_ms']:.0f} ms，写输出 {t['predicted_per_second']:.1f} 个/秒，"
          f"停因 {r.get('stop_type')}")
    return r["content"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", default="", help="替模型写好回答的开头")
    ap.add_argument("--once", action="store_true", help="让服务端一口气写完")
    ap.add_argument("--dump", help="把每一圈的前 3 名候选存成 JSON 文件")
    ap.add_argument("--ignore-end", action="store_true", help="挑中结束符也不停，看会写出什么")
    a = ap.parse_args()

    prefix = tokenize(chat_prompt(SYSTEM, f"{LOG}\n\n{QUESTION}"))
    print(f"输入一共 {len(prefix)} 个 token（含对话模板的特殊 token）\n")
    log: list = []
    text = once(prefix) if a.once else generate(prefix, a.force, a.ignore_end, log)
    if a.dump:
        with open(a.dump, "w", encoding="utf-8") as f:
            json.dump({"prompt_n": len(prefix), "steps": log}, f, ensure_ascii=False)
    print(f"\n回答：{text}")


if __name__ == "__main__":
    main()
