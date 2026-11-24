"""全景：一次排障提问，agent 来回了几轮。不用任何框架，手写 agent 循环（值班助手 v1）。

    python main.py                 # 连本机 llama-server（实现了 /v1/messages 和 tools）
    python main.py --approve       # 模型要 heap_dump 时自动同意（默认拒绝，模拟值班同学说“高峰期别 dump”）
    ANTHROPIC_BASE_URL= ANTHROPIC_API_KEY=sk-ant-... MODEL=claude-sonnet-5-5 python main.py   # 有 key 时连 Claude

每一轮打印：耗时、stop_reason、模型要了哪些工具、送回了多少字；最后打印整段对话存到哪。
"""

import json
import os
import sys
import time
from pathlib import Path

import anthropic

import oncall

BASE_URL = os.environ.get("ANTHROPIC_BASE_URL", "http://127.0.0.1:8090")
MODEL = os.environ.get("MODEL", "qwen3-1.7b")
client = anthropic.Anthropic(base_url=BASE_URL or None, api_key=os.environ.get("ANTHROPIC_API_KEY", "local-no-key"),
                             max_retries=2, timeout=180)

# 第 8 站：停止条件。下一步由模型决定，所以上限由你的代码定
MAX_STEPS = 15          # 最多调几次模型
MAX_TOKENS_TOTAL = 40_000   # 输入 + 输出 token 预算
MAX_SECONDS = 600       # 整次排查的墙钟时间（本机 CPU 慢；连 Claude 时 90 秒就够）
TOOL_RESULT_LIMIT = 1500    # 每个工具结果最多送回多少字


def ask_human(name: str, args: dict) -> bool:
    """第 7 站：危险操作先问人。这里用命令行参数代替真正的“点确认”。"""
    ok = "--approve" in sys.argv
    print(f"   ⚠ 模型要调用 {name}({args})，需要值班人员确认 → {'同意' if ok else '拒绝'}")
    return ok


def run_tool(block) -> dict:
    """第 4、5 站：按名字分派到你的函数，把结果包成 tool_result。模型只是“提议”，执行的是这里。"""
    name, args = block.name, dict(block.input or {})
    if name not in oncall.IMPL:
        return {"type": "tool_result", "tool_use_id": block.id, "is_error": True, "content": f"没有叫 {name} 的工具"}
    if name in oncall.NEEDS_APPROVAL and not ask_human(name, args):
        return {"type": "tool_result", "tool_use_id": block.id, "is_error": True,
                "content": "值班人员拒绝：晚高峰不做堆转储，请用已有证据给结论，并建议低峰时再 dump。"}
    try:
        out = oncall.IMPL[name](**args)
    except TypeError as e:                       # 模型传错参数是常事：把错误原样告诉它，让它改
        return {"type": "tool_result", "tool_use_id": block.id, "is_error": True, "content": f"参数不对：{e}"}
    return {"type": "tool_result", "tool_use_id": block.id, "content": out[:TOOL_RESULT_LIMIT]}


def main() -> None:
    messages = [{"role": "user", "content": oncall.QUESTION}]
    used, t_start = 0, time.perf_counter()
    for step in range(1, MAX_STEPS + 1):
        t0 = time.perf_counter()
        resp = client.messages.create(model=MODEL, max_tokens=1024, system=oncall.SYSTEM,
                                      tools=oncall.TOOLS, messages=messages)
        dt = time.perf_counter() - t0
        used += resp.usage.input_tokens + resp.usage.output_tokens
        calls = [b for b in resp.content if b.type == "tool_use"]
        print(f"第 {step} 轮：{dt:5.1f}s  stop_reason={resp.stop_reason}  "
              f"输入 {resp.usage.input_tokens} 输出 {resp.usage.output_tokens} token  "
              f"要了 {[f'{b.name}({json.dumps(b.input, ensure_ascii=False)})' for b in calls]}")
        messages.append({"role": "assistant", "content": [b.model_dump(exclude_none=True) for b in resp.content]})

        if resp.stop_reason != "tool_use":       # 第 9 站：不再要工具，就是答完了
            text = "".join(b.text for b in resp.content if b.type == "text")
            print("\n最终回答：\n" + text.strip())
            break
        results = [run_tool(b) for b in calls]   # 同一轮的多个 tool_use，结果放进同一条 user 消息
        for r in results:
            print(f"   ← {('错误 ' if r.get('is_error') else '')}{len(r['content'])} 字")
        messages.append({"role": "user", "content": results})

        if used > MAX_TOKENS_TOTAL or time.perf_counter() - t_start > MAX_SECONDS:
            print(f"\n超出预算（已用 {used} token，{time.perf_counter() - t_start:.0f}s），停下，把已有证据交给人")
            break
    else:
        print(f"\n到了 {MAX_STEPS} 步上限，停下")

    total = time.perf_counter() - t_start
    print(f"\n共 {step} 轮模型调用，{sum(1 for m in messages if m['role'] == 'user') - 1} 批工具结果，"
          f"{used} token，{total:.1f}s")
    out = Path(__file__).with_name("transcript.json")
    out.write_text(json.dumps(messages, ensure_ascii=False, indent=1))
    print(f"整段对话：{out}")


if __name__ == "__main__":
    main()
