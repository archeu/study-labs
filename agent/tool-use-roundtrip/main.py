"""工具调用的一来一回：模型提议，你的代码执行。只走一个来回，把每条消息的原样 JSON 打出来。

    python main.py            # 用 SDK 走一个来回：请求 → tool_use → 执行 → tool_result → 回答
    python main.py --raw      # 掀开盖子：看 tools 被渲染成了什么提示词，模型原始吐出的是什么字
    python main.py --tokens   # 量一量：5 份工具说明占多少输入 token

本机 llama-server（实现了 /v1/messages）+ Qwen3-1.7B；有 key 时设 ANTHROPIC_BASE_URL= 连 Claude（--raw 只对本机有效）。
"""

import json
import os
import sys
import time
from pathlib import Path

import urllib.request

import anthropic

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pano-04-triage-agent"))
import oncall  # noqa: E402

BASE_URL = os.environ.get("ANTHROPIC_BASE_URL", "http://127.0.0.1:8090")
MODEL = os.environ.get("MODEL", "qwen3-1.7b")
client = anthropic.Anthropic(base_url=BASE_URL or None, api_key=os.environ.get("ANTHROPIC_API_KEY", "local-no-key"),
                             timeout=300)

TOOLS = [t for t in oncall.TOOLS if t["name"] == "parse_gc_log"]       # 只给一个工具，来回最短
SYSTEM = "你是 order-svc 的值班排障助手。需要数据时调用工具，只用工具给的事实回答，回答不超过三句话。"
QUESTION = "20:16 告警：order-04 接口 P99 2.1 秒。帮我看看 20:00 以后的 GC 情况。"


def show(title, obj):
    print(f"\n===== {title} =====")
    print(json.dumps(obj, ensure_ascii=False, indent=1)[:1500])


def roundtrip():
    messages = [{"role": "user", "content": QUESTION}]
    show("请求 1：messages（tools 另外单独发）", messages)

    t0 = time.perf_counter()
    r1 = client.messages.create(model=MODEL, max_tokens=1024, system=SYSTEM, tools=TOOLS, messages=messages)
    print(f"\n[{time.perf_counter() - t0:.1f}s] stop_reason = {r1.stop_reason}")
    blocks = [b.model_dump(exclude_none=True) for b in r1.content]
    for b in blocks:                                   # 思考块太长，只留开头
        if b["type"] == "thinking":
            b["thinking"] = b["thinking"][:60] + "……"
    show("回应 1：content 块（模型的“调用单”）", blocks)
    if r1.stop_reason != "tool_use":
        print("模型没要工具，直接答了。")
        return

    messages.append({"role": "assistant", "content": [b.model_dump(exclude_none=True) for b in r1.content]})
    results = []
    for b in r1.content:
        if b.type == "tool_use":
            out = oncall.IMPL[b.name](**b.input)      # 执行的是你的代码，不是模型
            results.append({"type": "tool_result", "tool_use_id": b.id, "content": out})
    messages.append({"role": "user", "content": results})
    show("请求 2 新加的一条：role=user，里面是 tool_result", messages[-1])

    t0 = time.perf_counter()
    r2 = client.messages.create(model=MODEL, max_tokens=1024, system=SYSTEM, tools=TOOLS, messages=messages)
    print(f"\n[{time.perf_counter() - t0:.1f}s] stop_reason = {r2.stop_reason}")
    print("回答：", "".join(b.text for b in r2.content if b.type == "text").strip())
    print(f"\n请求 2 一共发了 {len(messages)} 条消息：user → assistant(tool_use) → user(tool_result)")


def raw():
    """llama-server 专有接口：/apply-template 看渲染后的提示词，/completion 看模型原始输出。"""
    msgs = [{"role": "system", "content": SYSTEM}, {"role": "user", "content": QUESTION}]
    oa_tools = [{"type": "function", "function": {"name": t["name"], "description": t["description"],
                                                  "parameters": t["input_schema"]}} for t in TOOLS]
    def post(path, body):
        req = urllib.request.Request(BASE_URL + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=300) as r:
            return json.load(r)

    prompt = post("/apply-template", {"messages": msgs, "tools": oa_tools})["prompt"]
    print("===== 模型真正看到的提示词（工具说明被写成了文字）=====")
    print(prompt)
    t0 = time.perf_counter()
    out = post("/completion", {"prompt": prompt, "n_predict": 600, "temperature": 0})
    print(f"===== 模型原始吐出的字（{time.perf_counter() - t0:.1f}s）=====")
    print(out["content"])


def tokens():
    q = [{"role": "user", "content": QUESTION}]
    base = client.messages.count_tokens(model=MODEL, system=oncall.SYSTEM, messages=q).input_tokens
    one = client.messages.count_tokens(model=MODEL, system=oncall.SYSTEM, messages=q, tools=TOOLS).input_tokens
    five = client.messages.count_tokens(model=MODEL, system=oncall.SYSTEM, messages=q, tools=oncall.TOOLS).input_tokens
    print(f"不带工具 {base} token；带 1 个工具 {one}（+{one - base}）；带 5 个工具 {five}（+{five - base}）")


if __name__ == "__main__":
    {"--raw": raw, "--tokens": tokens}.get(sys.argv[1] if len(sys.argv) > 1 else "", roundtrip)()
