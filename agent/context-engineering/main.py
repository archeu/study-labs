"""⑤ 套进场景：拼出 10:42 那次请求，逐块数 token，打出一张“桌子”预算表。

需要一个实现了 /v1/messages 的服务（本机用 llama.cpp 的 llama-server，见 README）：
    ANTHROPIC_BASE_URL=http://127.0.0.1:8090 uv run python context-engineering/main.py
"""

import os

import anthropic

from context_builder import MEMORIES, SYSTEM, TOOLS, build_request, truncate_tool_result

MODEL = os.environ.get("MODEL", "qwen3")
BUDGET = 32_000          # 这个服务给一次请求定的预算（不是模型上限）
THRESHOLD = int(BUDGET * 0.75)
RESERVE_OUTPUT = 2_000   # 给模型回答留的位置
QUESTION = "10:42 告警：order-svc P99 1.6 秒。是不是昨天那个问题？"

client = anthropic.Anthropic(base_url=os.environ.get("ANTHROPIC_BASE_URL", "http://127.0.0.1:8090"),
                             api_key=os.environ.get("ANTHROPIC_API_KEY", "local"))


def count(**req) -> int:
    return client.messages.count_tokens(model=MODEL, **req).input_tokens


def main():
    full = build_request(QUESTION, MEMORIES, now="2026-11-09 10:42", layout="good")
    q_only = [{"role": "user", "content": "?"}]
    steps = [
        ("工具说明 5 份", dict(tools=TOOLS, messages=q_only)),
        ("+ 系统提示", dict(tools=TOOLS, system=full["system"], messages=q_only)),
        ("+ 记忆 3 条 + 问题", dict(tools=TOOLS, system=full["system"], messages=full["messages"])),
    ]
    base = count(messages=q_only)
    prev = base
    print(f"{'块':<16}{'这块':>8}{'累计':>8}")
    for name, req in steps:
        n = count(**req)
        print(f"{name:<16}{n - prev:>8}{n:>8}")
        prev = n
    left = BUDGET - RESERVE_OUTPUT - prev
    print(f"预算 {BUDGET}，压缩阈值 {THRESHOLD}，给回答留 {RESERVE_OUTPUT}，还剩 {left} 给工具结果和对话")

    # 放多少：一次工具结果先截断再放
    big = "\n".join(f"GC({1200 + i}) Pause Young (Normal) 400M->180M(1024M) {30 + i % 50}.1ms" for i in range(3000))
    small = truncate_tool_result(big)
    for name, text in [("GC 日志原样 3000 行", big), ("截断后 40 行", small)]:
        n = count(messages=[{"role": "user", "content": text}]) - base
        print(f"{name:<18}{n:>8} token，占剩余的 {n / left:.0%}")
    print("系统提示字数", len(SYSTEM))


if __name__ == "__main__":
    main()
