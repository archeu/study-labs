"""上下文窗口：装不下会怎样、越长越慢多少、关键一行埋在中间还找不找得到。

    python main.py overflow   # 输入超过窗口：服务端直接拒绝
    python main.py speed      # 输入 500 / 1000 / 2000 / 4000 token：读输入耗时、写输出速度
    python main.py needle     # 把 Full GC 那一行埋进约 3000 token 的日志开头 / 中间 / 末尾，问最长停顿

连本机 llama-server（窗口 8192，-c 8192）。只用标准库。耗时以 2 核 CPU 为准，大模型服务快得多，但比例相近。
"""

import json
import os
import random
import sys
import time
import urllib.error
import urllib.request

SERVER = os.environ.get("LLAMA_URL", "http://127.0.0.1:8090")
SYSTEM = "只根据日志回答，日志里没有的不要编。"
NEEDLE = "[2026-11-08T20:12:43.207+0800][info][gc] GC(1222) Pause Full (G1 Compaction Pause) 1010M->702M(1024M) 1873.412ms"


def post(path: str, body: dict) -> dict:
    req = urllib.request.Request(SERVER + path, json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=900) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        return json.loads(e.read())


def chat(user: str) -> str:
    return (f"<|im_start|>system\n{SYSTEM}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n"
            f"<|im_start|>assistant\n")


def young_lines(n: int, seed: int = 7) -> list[str]:
    """n 行普通的 Young GC（停顿都在 10～240ms），时间从 20:00 往后排。"""
    rnd = random.Random(seed)
    out, t, gc = [], 0.0, 1000
    for _ in range(n):
        t += rnd.uniform(3, 9)
        gc += 1
        mm, ss = divmod(t, 60)
        before = rnd.randint(600, 1000)
        out.append(f"[2026-11-08T{20 + int(mm) // 60:02d}:{int(mm) % 60:02d}:{ss:06.3f}+0800][info][gc] GC({gc}) "
                   f"Pause Young (Normal) (G1 Evacuation Pause) {before}M->{before - rnd.randint(150, 300)}M(1024M) "
                   f"{rnd.uniform(10, 240):.3f}ms")
    return out


def ntok(text: str) -> int:
    return len(post("/tokenize", {"content": text, "parse_special": True})["tokens"])


def cmd_overflow() -> None:
    log = "\n".join(young_lines(120))
    prompt = chat(log + "\n\n最长的一次停顿是多少毫秒？")
    print(f"输入 {ntok(prompt)} 个 token，窗口 8192：")
    r = post("/completion", {"prompt": prompt, "n_predict": 50, "cache_prompt": False})
    print("  ", json.dumps(r.get("error", r.get("stop_type")), ensure_ascii=False))


def cmd_speed() -> None:
    lines = young_lines(120)
    print(f"{'输入 token':>10}{'读输入 ms':>11}{'读输入 个/秒':>13}{'写输出 个/秒':>13}")
    for n_lines in (6, 13, 26, 52):
        prompt = chat("\n".join(lines[:n_lines]) + "\n\n最长的一次停顿是多少毫秒？")
        r = post("/completion", {"prompt": prompt, "n_predict": 32, "temperature": 0,
                                 "cache_prompt": False, "ignore_eos": True})
        t = r["timings"]
        print(f"{t['prompt_n']:>10}{t['prompt_ms']:>11.0f}{t['prompt_per_second']:>13.1f}{t['predicted_per_second']:>13.1f}")


def cmd_needle() -> None:
    hay = young_lines(38, seed=11)
    question = "这段日志里最长的一次 GC 停顿是多少毫秒？是哪一种 GC？只回答一句。"
    for name, pos in (("开头", 1), ("中间", 19), ("末尾", 37)):
        lines = hay[:pos] + [NEEDLE] + hay[pos:]
        prompt = chat("\n".join(lines) + "\n\n" + question)
        t0 = time.time()
        r = post("/completion", {"prompt": prompt, "n_predict": 60, "temperature": 0, "cache_prompt": True})
        found = "1873" in r["content"]
        print(f"埋在{name}（第 {pos + 1} 行 / 共 {len(lines)} 行，{r['timings']['prompt_n']} 个 token 新算，"
              f"{time.time() - t0:.0f} s）：{'找到' if found else '没找到'}\n   {r['content'].strip()}")


if __name__ == "__main__":
    {"overflow": cmd_overflow, "speed": cmd_speed, "needle": cmd_needle}[sys.argv[1] if len(sys.argv) > 1 else "needle"]()
