"""Agent 循环：调到不再要工具或到上限为止。最小的手写循环，每轮打印 messages 有几条、完整提示多长、花了多久。

    python main.py           # 连本机 llama-server（Qwen3-1.7B）或 Claude（设 ANTHROPIC_BASE_URL= 和 key）
    python main.py --fake    # 用照剧本出牌的假模型，几毫秒跑完，看结构不看质量

和全景页 main.py 的区别：这里只留循环本身（没有审批、没有预算），方便看清骨架。
"""

import os
import sys
import time
from pathlib import Path

import anthropic

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pano-04-triage-agent"))
import oncall                      # noqa: E402
from fake_model import FakeClient  # noqa: E402

FAKE = "--fake" in sys.argv
BASE_URL = os.environ.get("ANTHROPIC_BASE_URL", "http://127.0.0.1:8090")
MODEL = os.environ.get("MODEL", "qwen3-1.7b")
real = anthropic.Anthropic(base_url=BASE_URL or None, api_key=os.environ.get("ANTHROPIC_API_KEY", "local-no-key"), timeout=300)
client = FakeClient([[("search_logs", {"keyword": "WARN|ERROR", "since": "20:00"}), ("parse_gc_log", {"since": "20:00"})],
                     [("thread_dump", {})], "结论：20:00 上线的导出把 41 万行读进内存……"]) if FAKE else real

TOOLS = [t for t in oncall.TOOLS if t["name"] != "heap_dump"]    # 这一页只看循环，不带要审批的工具
MAX_STEPS = 15


def prompt_tokens(messages) -> int:
    """完整提示有多长：每轮都整段重发，所以它一轮比一轮大。假模型时粗估。"""
    if FAKE:
        return len(str(messages)) // 3
    return real.messages.count_tokens(model=MODEL, system=oncall.SYSTEM, tools=TOOLS, messages=messages).input_tokens


def agent(question: str) -> str:
    messages = [{"role": "user", "content": question}]
    total_in = 0
    for step in range(1, MAX_STEPS + 1):                        # 上限：下一步由模型定，停由你兜底
        n_tok = prompt_tokens(messages)
        total_in += n_tok
        t0 = time.perf_counter()
        resp = client.messages.create(model=MODEL, max_tokens=2048, system=oncall.SYSTEM, tools=TOOLS, messages=messages)
        calls = [b for b in resp.content if b.type == "tool_use"]
        print(f"第 {step} 轮：发出 {len(messages):2} 条消息 / {n_tok:5} token，{time.perf_counter() - t0:5.1f}s，"
              f"stop_reason={resp.stop_reason}，要了 {[b.name for b in calls]}")
        messages.append({"role": "assistant", "content": [b.model_dump(exclude_none=True) for b in resp.content]})

        if resp.stop_reason == "end_turn":                      # 正常出口：不再要工具
            print(f"\n共 {step} 轮，累计发出输入 {total_in} token（每轮整段重发的总和）")
            return "".join(b.text for b in resp.content if b.type == "text").strip()
        if resp.stop_reason != "tool_use":                      # max_tokens、refusal……都不能当答完
            return f"[异常结束：stop_reason={resp.stop_reason}]"

        results = []
        for b in calls:
            try:
                out = oncall.IMPL[b.name](**b.input)
                results.append({"type": "tool_result", "tool_use_id": b.id, "content": out[:1500]})
            except (KeyError, TypeError) as e:
                results.append({"type": "tool_result", "tool_use_id": b.id, "is_error": True, "content": f"调用不对：{e!r}"})
        messages.append({"role": "user", "content": results})
    return f"[到了 {MAX_STEPS} 步上限，停下]"


if __name__ == "__main__":
    print("\n最终回答：\n" + agent(oncall.QUESTION))
