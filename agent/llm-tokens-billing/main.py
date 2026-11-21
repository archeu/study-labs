"""token、分词和计费：同一段日志切成多少 token、要花多少钱。

    python main.py split     # 几类文本各切成多少 token，看切在哪
    python main.py log       # 值班助手的 61 行 GC 日志：原样、瘦身两种，各多少 token、多少钱

分词用本机 llama-server 里 Qwen2.5 的分词器（/tokenize）。Claude 的分词器不同，
真要算 Claude 的数，用 POST /v1/messages/count_tokens（要 API key）。只用标准库。
"""

import json
import os
import random
import re
import sys
import urllib.request

SERVER = os.environ.get("LLAMA_URL", "http://127.0.0.1:8090")

# 美元 / 百万 token（Claude 官方价格，2026-11 核对）
PRICES = {
    "Claude Opus 5.5":   {"in": 4.00, "out": 20.00},
    "Claude Sonnet 5.5": {"in": 2.00, "out": 10.00},
    "Claude Haiku 5.5":  {"in": 0.10, "out": 0.50},
}


def post(path: str, body: dict) -> dict:
    req = urllib.request.Request(SERVER + path, json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)


def pieces(text: str) -> list:
    """切成 token，返回每段的文字（半个汉字的段落返回字节列表）。"""
    return [t["piece"] for t in post("/tokenize", {"content": text, "with_pieces": True})["tokens"]]


def count(text: str) -> int:
    return len(post("/tokenize", {"content": text, "parse_special": True})["tokens"])


def show(p) -> str:
    return "<" + " ".join(f"{b:02x}" for b in p) + ">" if isinstance(p, list) else p.replace("\n", "↵")


SAMPLES = {
    "英文句子": "The order service slowed down at 20:15 because of a long GC pause.",
    "中文句子": "订单服务在 20:15 变慢，是因为一次很长的 GC 停顿。",
    "GC 日志": "[2026-11-08T20:12:43.207+0800][info][gc] GC(1214) Pause Full (G1 Compaction Pause) 1010M->702M(1024M) 1873.412ms",
    "Java 栈": "\tat com.shop.order.OrderService.create(OrderService.java:142)",
    "JSON": '{"orderId":"ORD-20261108-000123","userId":1024,"amount":176.00,"status":"PAID"}',
    "UUID": "3f2b8c1e-9a4d-4e6b-b0c7-5d1e2f3a4b5c",
}


def cmd_split() -> None:
    print(f"{'类型':<8}{'字符':>5}{'字节':>5}{'token':>6}{'字符/token':>11}  切法")
    for name, text in SAMPLES.items():
        ps = pieces(text)
        print(f"{name:<8}{len(text):>5}{len(text.encode()):>5}{len(ps):>6}{len(text)/len(ps):>11.2f}  "
              + " | ".join(show(p) for p in ps[:14]) + (" …" if len(ps) > 14 else ""))


def gc_log() -> str:
    """order-04 在 20:06～20:16 的 61 行 G1 日志（固定随机种子，每次一样）。"""
    rnd = random.Random(42)
    lines, t, gc, heap, full_done = [], 6 * 60 + 2.0, 1180, 300, False
    for i in range(61):
        t += rnd.uniform(6, 13)
        gc += 1
        if not full_done and t >= 12 * 60 + 43:  # 20:12:43 那次 Full GC
            t, full_done, heap = 12 * 60 + 43.207, True, 702
            lines.append(f"[2026-11-08T20:12:43.207+0800][info][gc] GC({gc}) Pause Full (G1 Compaction Pause) 1010M->702M(1024M) 1873.412ms")
            continue
        mm, ss = divmod(t, 60)
        ts = f"[2026-11-08T20:{int(mm):02d}:{ss:06.3f}+0800]"
        before = min(1000, heap + rnd.randint(180, 320))
        after = max(150, before - rnd.randint(150, 260))
        kind = "Pause Young (Concurrent Start) (G1 Humongous Allocation)" if i % 17 == 16 else "Pause Young (Normal) (G1 Evacuation Pause)"
        lines.append(f"{ts}[info][gc] GC({gc}) {kind} {before}M->{after}M(1024M) {rnd.uniform(8, 240):.3f}ms")
        heap = after
    return "\n".join(lines)


def slim(log: str) -> str:
    """瘦身：时间戳只留时分秒，去掉每行都一样的 [info][gc] 和括号里的原因，只留 >100ms 的停顿。"""
    out = []
    for line in log.splitlines():
        m = re.match(r"\[2026-11-08T(\d\d:\d\d:\d\d)\.\d+\+0800\]\[info\]\[gc\] GC\(\d+\) (Pause \w+).*? (\d+M->\d+M)\(1024M\) ([\d.]+)ms", line)
        if m and float(m.group(4)) > 100:
            out.append(f"{m.group(1)} {m.group(2)} {m.group(3)} {float(m.group(4)):.0f}ms")
    return "堆上限 1024M，只列停顿超过 100ms 的 GC：\n" + "\n".join(out)


def chat(system: str, user: str) -> str:
    return (f"<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n"
            f"<|im_start|>assistant\n")


def cmd_log() -> None:
    system = "只根据日志回答，日志里没有的不要编。"
    question = "order-04 在 20:15 变慢，是不是 GC 导致的？用一句话回答，并引用最关键的一行日志。"
    full, small = gc_log(), slim(gc_log())
    out_tokens = 300
    for name, log in [("原样 61 行", full), ("瘦身后", small)]:
        n = count(chat(system, f"{log}\n\n{question}"))
        print(f"\n{name}：{len(log.splitlines())} 行，{len(log)} 个字符 → 整个请求 {n} 个 token（Qwen 分词器）")
        for model, p in PRICES.items():
            one = (n * p["in"] + out_tokens * p["out"]) / 1e6
            print(f"   {model:<18} 一次 ${one:.5f}  （输入 ${n*p['in']/1e6:.5f} + 输出 {out_tokens} 个 ${out_tokens*p['out']/1e6:.5f}）"
                  f"   每天 2000 次 ${one*2000:.2f}")
    print("\n瘦身后的日志：\n" + small)


if __name__ == "__main__":
    {"split": cmd_split, "log": cmd_log}.get(sys.argv[1] if len(sys.argv) > 1 else "split", cmd_split)()
