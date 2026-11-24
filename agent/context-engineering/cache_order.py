"""实验：记忆放哪，决定了第二次请求能复用多少前缀缓存。

两种拼法各发两次请求（同一个服务、两个不同的问题、记忆也不同），看第二次：
  cache_read_input_tokens —— 命中前缀缓存、不用重算的 token
  input_tokens            —— 要重新算的 token（llama-server 只报没命中的部分）
  耗时                    —— 本机 CPU 上，重算的 token 越多越慢
    ANTHROPIC_BASE_URL=http://127.0.0.1:8090 uv run python context-engineering/cache_order.py
服务端用一个槽（-np 1），它会把上一次请求的 KV 留在槽里；两种拼法开头就不同，换拼法时天然是“冷”的。
"""

import os
import time

import anthropic

from context_builder import MEMORIES, build_request

MODEL = os.environ.get("MODEL", "qwen3")
client = anthropic.Anthropic(base_url=os.environ.get("ANTHROPIC_BASE_URL", "http://127.0.0.1:8090"),
                             api_key=os.environ.get("ANTHROPIC_API_KEY", "local"), timeout=900)

REQS = [
    ("2026-11-09 10:42", "10:42 告警：order-svc P99 1.6 秒。是不是昨天那个问题？", MEMORIES),
    ("2026-11-09 10:57", "order-svc 的线程池现在有没有打满？", [MEMORIES[2], MEMORIES[1]]),
]


def run(layout: str):
    for i, (now, q, mems) in enumerate(REQS, 1):
        req = build_request(q, mems, now=now, layout=layout)
        t = time.perf_counter()
        r = client.messages.create(model=MODEL, max_tokens=1, **req,
                                   extra_body={"chat_template_kwargs": {"enable_thinking": False}})
        dt = time.perf_counter() - t
        u = r.usage
        print(f"{layout:<5} 第 {i} 次  命中 {u.cache_read_input_tokens or 0:>5}  重算 {u.input_tokens:>5}  耗时 {dt:6.1f}s")


if __name__ == "__main__":
    for layout in ("bad", "good"):
        run(layout)
