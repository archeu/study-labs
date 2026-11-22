"""推理模型和“思考”：同一道数数题，关掉思考 vs 打开思考，对了几次、花了多少 token 和时间。

    python main.py            # 两种模式各跑 3 次（seed 1～3）
    python main.py --show     # 额外打印一次完整的思考过程

用本机第二个 llama-server：Qwen3-0.6B（能开关思考），端口 8091，启动时加 --jinja。
关思考用 chat_template_kwargs.enable_thinking=false。只用标准库。
"""

import json
import os
import re
import sys
import time
import urllib.request

SERVER = os.environ.get("THINK_URL", "http://127.0.0.1:8091")

PAUSES = [("Young", 236.871), ("Young", 61.204), ("Young", 158.204), ("Full", 1873.412), ("Young", 312.530),
          ("Young", 88.019), ("Young", 140.870), ("Young", 240.115), ("Young", 45.302), ("Young", 95.006)]
LOG = "\n".join(
    f"[2026-11-08T20:{10 + i // 6:02d}:{(i * 9) % 60:02d}.{(i * 137) % 1000:03d}+0800][info][gc] GC({1210 + i}) "
    f"Pause {k} ({'G1 Compaction Pause' if k == 'Full' else 'Normal'}) {900 - i * 7}M->{600 - i * 5}M(1024M) {ms:.3f}ms"
    for i, (k, ms) in enumerate(PAUSES))
TRUTH = sum(1 for _, ms in PAUSES if ms > 200)
QUESTION = "上面这段 GC 日志里，停顿时间超过 200ms 的 GC 一共有几次？把次数写在 <answer></answer> 里，例如 <answer>7</answer>。"


def post(path: str, body: dict) -> dict:
    req = urllib.request.Request(SERVER + path, json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        return json.load(r)


def ask(thinking: bool, seed: int, max_tokens: int = 3000) -> dict:
    t0 = time.time()
    r = post("/v1/chat/completions", {
        "messages": [{"role": "user", "content": LOG + "\n\n" + QUESTION}],
        "max_tokens": max_tokens, "temperature": 0.6, "top_p": 0.95, "seed": seed,
        "chat_template_kwargs": {"enable_thinking": thinking},
    })
    m = r["choices"][0]["message"]
    answer = m["content"].strip()
    m2 = re.search(r"<answer>\s*(\d+)\s*</answer>", answer)
    return {"answer": answer, "thought": m.get("reasoning_content") or "", "got": int(m2.group(1)) if m2 else None,
            "out": r["usage"]["completion_tokens"], "sec": time.time() - t0,
            "finish": r["choices"][0]["finish_reason"]}


def main() -> None:
    print(f"日志 {len(PAUSES)} 行，停顿超过 200ms 的真实答案：{TRUTH} 次\n")
    show = "--show" in sys.argv
    for thinking in (False, True):
        name = "打开思考" if thinking else "关掉思考"
        ok = 0
        for seed in (1, 2, 3):
            r = ask(thinking, seed)
            ok += r["got"] == TRUTH
            vis = len(post("/tokenize", {"content": r["answer"]})["tokens"])
            print(f"{name} seed={seed}：答 {r['got']}（{'对' if r['got'] == TRUTH else '错'}），"
                  f"计费的输出 {r['out']:>4} 个 token，看得见的回答只有 {vis:>3} 个，"
                  f"用时 {r['sec']:5.1f} s，结束原因 {r['finish']}")
            if show and thinking and seed == 1:
                print("   ── 思考过程 ──\n   " + r["thought"].strip().replace("\n", "\n   ")[:3000])
        print(f"{name}：3 次对了 {ok} 次\n")
    r = ask(True, 2, max_tokens=300)
    print(f"打开思考、max_tokens=300：结束原因 {r['finish']}，思考 {len(r['thought'])} 个字符，"
          f"看得见的回答 {r['answer']!r}，答 {r['got']}")


if __name__ == "__main__":
    main()
