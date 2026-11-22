"""流式输出：同一个问题，流式和非流式的首字时间；事件清点；流到一半出错时 SDK 怎么做。

    python main.py compare     # 非流式 vs 流式：拿到第一个字的时间、总时间（本机 llama-server）
    python main.py events      # 清点一次流里每种事件有几个，message_delta 里带了什么
    python main.py midstream   # 连 fake_server.py：推了 3 段后来一个 overloaded_error
官方 anthropic SDK。
"""

import os
import sys
import time

import anthropic

LOCAL = os.environ.get("ANTHROPIC_BASE_URL", "http://127.0.0.1:8090")
MODEL = os.environ.get("MODEL", "qwen2.5-0.5b")
KEY = os.environ.get("ANTHROPIC_API_KEY", "local-no-key")
# 本机服务默认温度 0.8，两次输出会不一样；固定成 0 才好比时间（SDK 2.x 没有 temperature 参数，用 extra_body，连 Claude 时去掉）
EXTRA = {"temperature": 0} if LOCAL else {}
SYSTEM = "你是值班助手。"
ASK = [{"role": "user", "content": "order-04 在 20:12:43 有一次 1873ms 的 Full GC。用 5 条要点写出排查步骤，每条一句话。"}]


def cmd_compare() -> None:
    c = anthropic.Anthropic(base_url=LOCAL or None, api_key=KEY)
    c.messages.create(model=MODEL, max_tokens=1, system=SYSTEM, messages=ASK)       # 预热：前缀进缓存，两种都从热的开始
    t0 = time.perf_counter()
    m = c.messages.create(model=MODEL, max_tokens=200, system=SYSTEM, messages=ASK, extra_body=EXTRA)
    t1 = time.perf_counter() - t0
    print(f"非流式：{t1:5.2f} s 后一次拿到全部 {m.usage.output_tokens} 个 token（第一个字也是这时候才看到）")
    t0, first = time.perf_counter(), None
    with c.messages.stream(model=MODEL, max_tokens=200, system=SYSTEM, messages=ASK, extra_body=EXTRA) as s:
        for text in s.text_stream:                       # SDK 帮你只挑出文字增量
            if first is None:
                first = time.perf_counter() - t0
        final = s.get_final_message()
    print(f"流式：  {first:5.2f} s 看到第一个字，{time.perf_counter() - t0:5.2f} s 收完 {final.usage.output_tokens} 个 token")


def cmd_events() -> None:
    c = anthropic.Anthropic(base_url=LOCAL or None, api_key=KEY)
    counts, order = {}, []
    with c.messages.stream(model=MODEL, max_tokens=60, system=SYSTEM, messages=ASK) as s:
        for ev in s:
            if ev.type == "text":          # SDK 自己合成的便利事件（每个文字增量一份），不是服务端发的 SSE 事件
                continue
            counts[ev.type] = counts.get(ev.type, 0) + 1
            if not order or order[-1] != ev.type:
                order.append(ev.type)
            if ev.type == "message_delta":
                print("message_delta 带来：stop_reason =", ev.delta.stop_reason, "，usage =", ev.usage)
    print("事件顺序（相同的合并）：", " → ".join(order))
    print("个数：", counts)


def cmd_midstream() -> None:
    c = anthropic.Anthropic(base_url="http://127.0.0.1:8096", api_key=KEY, max_retries=2)
    got = []
    try:
        with c.messages.stream(model="fake-midstream", max_tokens=100, messages=ASK) as s:
            for text in s.text_stream:
                got.append(text)
                print("收到：", repr(text))
    except anthropic.APIStatusError as e:
        print(f"SDK 抛出 {type(e).__name__}：status={e.status_code}，{str(e)[:80]}")
    except Exception as e:  # noqa: BLE001
        print(f"SDK 抛出 {type(e).__name__}：{str(e)[:120]}")
    print(f"出错前已经收到 {len(got)} 段：{''.join(got)!r}")


if __name__ == "__main__":
    {"compare": cmd_compare, "events": cmd_events, "midstream": cmd_midstream}[sys.argv[1] if len(sys.argv) > 1 else "compare"]()
