"""Messages API：看清请求和响应的形状；多轮对话其实是每次把历史重发一遍。

    python main.py raw        # 打印一次调用真正发出去的 JSON 和收回来的 JSON
    python main.py history    # 追问“刚才那次停了多久”：不带历史 vs 带上历史
    python main.py grow       # 连续 4 轮，看每轮发出去几条消息、输入多少 token

官方 anthropic SDK，base_url 指向本机 llama-server（实现了 /v1/messages）。有 key 时设 ANTHROPIC_BASE_URL= 和 MODEL 连 Claude。
"""

import json
import os
import sys

import anthropic

BASE_URL = os.environ.get("ANTHROPIC_BASE_URL", "http://127.0.0.1:8090")
MODEL = os.environ.get("MODEL", "qwen2.5-0.5b")
SYSTEM = "你是值班助手。回答简短，一两句话。"


def log_request(req) -> None:            # SDK 2.x 底层是 httpx2，事件钩子拿到的是它的 Request
    print(">>> POST", req.url.path)
    print(json.dumps(json.loads(req.content), ensure_ascii=False, indent=2))


def make_client(show: bool = False) -> anthropic.Anthropic:
    hc = anthropic.DefaultHttpxClient(event_hooks={"request": [log_request]}) if show else None
    return anthropic.Anthropic(base_url=BASE_URL or None, http_client=hc,
                               api_key=os.environ.get("ANTHROPIC_API_KEY", "local-no-key"))


def cmd_raw() -> None:
    c = make_client(show=True)
    msg = c.messages.create(model=MODEL, max_tokens=60, system=SYSTEM,
                            messages=[{"role": "user", "content": "order-04 在 20:12:43 有一次 Full GC，停了 1873ms。记住这个数。"}])
    print("<<< 响应")
    print(json.dumps(msg.model_dump(exclude_none=True), ensure_ascii=False, indent=2))


def cmd_history() -> None:
    c = make_client()
    first = {"role": "user", "content": "order-04 在 20:12:43 有一次 Full GC，停了 1873ms。记住这个数。"}
    a1 = c.messages.create(model=MODEL, max_tokens=60, system=SYSTEM, messages=[first])
    print("第 1 轮 答：", a1.content[0].text)
    ask = {"role": "user", "content": "刚才那次 Full GC 停了多少毫秒？只回答数字。"}
    no_hist = c.messages.create(model=MODEL, max_tokens=30, system=SYSTEM, messages=[ask])
    print(f"\n第 2 轮 只发最新一句（messages 1 条，输入 {no_hist.usage.input_tokens + (no_hist.usage.cache_read_input_tokens or 0)} token）：", no_hist.content[0].text)
    hist = [first, {"role": "assistant", "content": a1.content}, ask]   # 把上一轮的 content 原样放回去
    with_hist = c.messages.create(model=MODEL, max_tokens=30, system=SYSTEM, messages=hist)
    print(f"第 2 轮 带上历史（messages 3 条，输入 {with_hist.usage.input_tokens + (with_hist.usage.cache_read_input_tokens or 0)} token）：", with_hist.content[0].text)


def cmd_grow() -> None:
    c = make_client()
    turns = ["order-04 在 20:12:43 有一次 Full GC，停了 1873ms。", "堆上限是多少？日志里写的是 1024M。",
             "Full GC 前后堆从多少降到多少？是 1010M 降到 702M。", "把前面三条信息总结成一句话。"]
    messages = []
    for i, t in enumerate(turns, 1):
        messages.append({"role": "user", "content": t})
        r = c.messages.create(model=MODEL, max_tokens=80, system=SYSTEM, messages=messages)
        total_in = r.usage.input_tokens + (r.usage.cache_read_input_tokens or 0)
        print(f"第 {i} 轮：发出 {len(messages)} 条消息，输入 {total_in:>3} token（其中缓存读 {r.usage.cache_read_input_tokens or 0:>3}），"
              f"输出 {r.usage.output_tokens:>2}  答：{r.content[0].text.strip()[:40]}")
        messages.append({"role": "assistant", "content": r.content})


if __name__ == "__main__":
    {"raw": cmd_raw, "history": cmd_history, "grow": cmd_grow}[sys.argv[1] if len(sys.argv) > 1 else "raw"]()
