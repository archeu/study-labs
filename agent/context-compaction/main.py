"""⑤ 亲眼看到：第 4 轮之后上下文太大了，三种腾地方的办法各省多少、各丢了什么。

    ANTHROPIC_BASE_URL=http://127.0.0.1:8090 uv run python context-compaction/main.py
本机用 llama-server + Qwen3-1.7B；换成 Claude 只要去掉 base_url 和 extra_body。
"""

import copy
import os
import re
import time

import anthropic

from compact import clear_old_tool_results, compacted_messages, run_subagent, summarize
from scene import TRUTH, gc_log, heap_histo, history

MODEL = os.environ.get("MODEL", "qwen3")
KW = {"extra_body": {"chat_template_kwargs": {"enable_thinking": False}}}  # 本地模型关掉思考，省时间
client = anthropic.Anthropic(base_url=os.environ.get("ANTHROPIC_BASE_URL", "http://127.0.0.1:8090"),
                             api_key=os.environ.get("ANTHROPIC_API_KEY", "local"), timeout=1800)

LONGEST_AT = re.search(r"T(10:\d\d:\d\d)\.\d+\+0800\]\S* GC\(\d+\) Pause Full \S+ \S+ \S+ \S+ 2311", gc_log()).group(1)
PROBES = [
    ("数字", "（值班人员追问）今天 10:00 以来 Full GC 一共几次？最长一次停顿多少毫秒？Full GC 后老年代占百分之多少？只用前面出现过的数字，一行回答。",
     [TRUTH["full_gc"], TRUTH["max_pause"], TRUTH["old_after"]]),
    ("细节", "（值班人员追问）停顿最长的那次 Full GC 发生在几点几分几秒？只用前面出现过的信息，一行回答；不知道就说不知道。",
     [LONGEST_AT]),
]


def tokens(req) -> int:
    return client.messages.count_tokens(model=MODEL, system=req["system"], tools=req["tools"],
                                        messages=req["messages"]).input_tokens


def ask(req, text):
    msgs = copy.deepcopy(req["messages"])
    msgs[-1]["content"].append({"type": "text", "text": text})
    r = client.messages.create(model=MODEL, max_tokens=120, system=req["system"], tools=req["tools"],
                               messages=msgs, **KW)
    return "".join(b.text for b in r.content if b.type == "text").strip().replace("\n", " ")


def main():
    req = history()
    variants = {"原样": req}
    variants["清旧结果"] = {**req, "messages": clear_old_tool_results(req["messages"], keep=1)}
    t = time.perf_counter()
    summary = summarize(client, MODEL, req, **KW)
    print(f"写摘要用了 {time.perf_counter() - t:.0f}s，摘要全文：\n{summary}\n")
    variants["摘要"] = {**req, "messages": compacted_messages(req, summary)}

    print(f"真相：Full GC {TRUTH['full_gc']} 次，最长 {TRUTH['max_pause']} ms（{LONGEST_AT}），老年代 {TRUTH['old_after']}%\n")
    for name, v in variants.items():
        print(f"== {name}：{tokens(v)} token")
        for pname, q, want in PROBES:
            ans = ask(v, q)
            hit = [w for w in want if w in ans]
            print(f"   [{pname}] {'✓' if len(hit) == len(want) else '✗'} 命中 {len(hit)}/{len(want)}  {ans[:110]}")

    print("\n== 子任务隔离：堆直方图")
    histo = heap_histo()
    task = "这是 order-svc 刚才的 jmap -histo 输出。找出占用最多的前 3 个类、各占多少字节，判断最可疑的是哪一个、它可能来自哪段代码。"
    t = time.perf_counter()
    answer, read = run_subagent(client, MODEL, task, histo, **KW)
    print(f"子 agent 读了 {read} token，用了 {time.perf_counter() - t:.0f}s，交回：\n{answer}")
    base = tokens(variants["清旧结果"])
    raw = copy.deepcopy(variants["清旧结果"])
    raw["messages"][-1]["content"].append({"type": "text", "text": histo})
    sub = copy.deepcopy(variants["清旧结果"])
    sub["messages"][-1]["content"].append({"type": "text", "text": "子任务结论：" + answer})
    print(f"主上下文：直接塞直方图 +{tokens(raw) - base} token；只收子 agent 结论 +{tokens(sub) - base} token")


if __name__ == "__main__":
    main()
