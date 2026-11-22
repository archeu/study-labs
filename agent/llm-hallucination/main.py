"""为什么会一本正经地编：日志里有的、没有的、要数的，各问一次；再用程序核对。

    python main.py

两种提示词各问 3 个问题（温度 0），再用“先查再答”问一遍：
  普通版：只根据日志回答
  严格版：日志里没有就写“日志里没有”；必须把依据行原样放进 <quote></quote>
每个回答都用程序核对：回答里的数字在不在日志里、<quote> 里的句子在不在日志里，
并打印回答里数字 token 的最低概率（模型“有多确定”）。连本机 llama-server（Qwen2.5-0.5B）。只用标准库。
"""

import json
import math
import os
import re
import urllib.request

SERVER = os.environ.get("LLAMA_URL", "http://127.0.0.1:8090")
LOG = """\
[2026-11-08T20:12:03.512+0800][info][gc] GC(1213) Pause Young (Normal) (G1 Evacuation Pause) 412M->188M(1024M) 236.871ms
[2026-11-08T20:12:43.207+0800][info][gc] GC(1214) Pause Full (G1 Compaction Pause) 1010M->702M(1024M) 1873.412ms
[2026-11-08T20:13:10.033+0800][info][gc] GC(1215) Pause Young (Normal) (G1 Evacuation Pause) 905M->711M(1024M) 198.204ms"""

QUESTIONS = [
    ("日志里有", "Full GC 那一次停顿了多少毫秒？"),
    ("日志里没有", "order-04 用的是哪个 JDK 版本？"),
    ("要数", "这段日志里一共有几次 Young GC？"),
]
PLAIN = "只根据日志回答，日志里没有的不要编。用一句话回答。"
STRICT = ("只根据日志回答。日志里找不到答案时，只回答“日志里没有”。"
          "否则先把作为依据的那一行日志原样放进 <quote></quote>，再用一句话回答。")


def post(path: str, body: dict) -> dict:
    req = urllib.request.Request(SERVER + path, json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.load(r)


def chat(system: str, user: str) -> str:
    return (f"<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n"
            f"<|im_start|>assistant\n")


def ask(system: str, q: str) -> dict:
    r = post("/completion", {"prompt": chat(system, LOG + "\n\n" + q), "n_predict": 120,
                             "temperature": 0, "n_probs": 1, "cache_prompt": True})
    probs = r.get("completion_probabilities", [])
    digit_p = [math.exp(t["logprob"]) for t in probs if re.fullmatch(r"\s*\d+\s*", t["token"])]
    return {"text": r["content"].strip(), "min_digit_p": min(digit_p) if digit_p else None}


def check(text: str) -> list[str]:
    """程序核对：这一步不靠模型。"""
    notes = []
    for q in re.findall(r"<quote>(.*?)</quote>", text, re.S):
        notes.append(("引用在日志里 ✓" if q.strip() in LOG else "引用不在日志里 ✗") + f"：{q.strip()[:50]}")
    body = re.sub(r"<quote>.*?</quote>", "", text, flags=re.S)
    for n in re.findall(r"\d+(?:\.\d+)?", body):
        if n not in LOG:
            notes.append(f"数字 {n} 不在日志里 ✗")
    return notes


def retrieve_then_answer(kind: str, q: str) -> str:
    """先查再答：程序负责找证据和数数，模型只负责把证据说成一句话。"""
    lines = LOG.splitlines()
    if "JDK" in q or "版本" in q:
        hits = [l for l in lines if re.search(r"jdk|java|version|版本", l, re.I)]
        if not hits:
            return "日志里没有（程序检索 jdk/java/version 无命中，没有调用模型）"
    if "几次" in q:
        n = sum("Pause Young" in l for l in lines)
        evidence = f"程序数出的结果：Pause Young 一共 {n} 行。"
    else:
        hits = [l for l in lines if "Full" in l]
        evidence = "\n".join(hits)
    r = post("/completion", {"prompt": chat("只根据给你的证据回答，用一句话。", evidence + "\n\n" + q),
                             "n_predict": 60, "temperature": 0, "cache_prompt": True})
    return r["content"].strip() + f"   〔给模型的证据：{evidence[:60]}…〕"


def main() -> None:
    truth = {"日志里有": "1873.412ms", "日志里没有": "日志里没有", "要数": "2 次"}
    for name, system in (("普通版", PLAIN), ("严格版", STRICT)):
        print(f"===== {name} =====")
        for kind, q in QUESTIONS:
            r = ask(system, q)
            p = f"{r['min_digit_p']:.3f}" if r["min_digit_p"] is not None else "—"
            print(f"[{kind}] {q}（正确：{truth[kind]}）\n   答：{r['text']}\n   数字 token 最低概率：{p}")
            for n in check(r["text"]):
                print("   程序核对：" + n)
        print()
    print("===== 先查再答 =====")
    for kind, q in QUESTIONS:
        print(f"[{kind}] {q}（正确：{truth[kind]}）\n   答：{retrieve_then_answer(kind, q)}")


if __name__ == "__main__":
    main()
