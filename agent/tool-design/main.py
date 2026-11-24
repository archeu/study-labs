"""工具怎么设计：同一个问题，两版工具说明，看模型第一轮填了什么参数、查没查到东西。

    python main.py --runs 3     # v1（全景页那版）和 v2（改过描述、默认值、空结果提示）各跑 3 次，只看第一轮
    python main.py --runs 3 --only v2   # 只跑一版
    python main.py --sizes      # 同一份信息，原样返回和精简返回各占多少 token

全景那次实测：模型把 since 填成告警时间 20:16，正好错过 20:09 开始的现场，两个工具都“没查到”。
v2 只改了说明书和返回，不改模型、不改系统提示。
"""

import copy
import os
import re
import sys
import time
from pathlib import Path

import anthropic

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pano-04-triage-agent"))
import oncall  # noqa: E402

BASE_URL = os.environ.get("ANTHROPIC_BASE_URL", "http://127.0.0.1:8090")
MODEL = os.environ.get("MODEL", "qwen3-1.7b")
client = anthropic.Anthropic(base_url=BASE_URL or None, api_key=os.environ.get("ANTHROPIC_API_KEY", "local-no-key"), timeout=300)

V1 = [t for t in oncall.TOOLS if t["name"] in ("search_logs", "parse_gc_log", "thread_dump")]

SINCE_V2 = {"type": "string", "pattern": "^[0-2][0-9]:[0-5][0-9]$",
            "description": "从几点开始查，HH:MM。告警通常在问题开始 5～15 分钟后才触发，所以填告警时间往前 15 分钟："
                           "告警 20:16 就填 20:00。不确定就不填，默认查告警前 30 分钟。"}
V2 = copy.deepcopy(V1)
for t in V2:
    if t["name"] == "search_logs":
        t["description"] = ("在 order-svc 的应用日志里按正则查日志行，返回最多 20 行（时间 级别 线程 内容）。"
                            "查报错、慢请求、最近的上线记录时用；看 GC 停顿请用 parse_gc_log。"
                            "查不到时会告诉你查的时间范围，可以放宽时间或换关键字再查一次。")
        t["input_schema"]["properties"]["keyword"]["description"] = "正则，例如 WARN|ERROR、耗时、上线|Export。中文日志，用中文关键字。"
        t["input_schema"]["properties"]["since"] = SINCE_V2
    if t["name"] == "parse_gc_log":
        t["description"] = ("解析 order-svc 的 GC 日志，返回一行摘要：停顿次数、最长停顿、Full GC 次数和每次时长、"
                            "Full GC 后堆还占多少、有没有大对象分配。怀疑 GC 导致变慢时用；不返回原始日志行。")
        t["input_schema"]["properties"]["since"] = SINCE_V2


def run_v2(name, args):
    """v2 的执行：since 不填用默认值；空结果说清楚查了什么范围、下一步可以怎么做。"""
    args = dict(args)
    if name in ("search_logs", "parse_gc_log"):
        args.setdefault("since", "19:46")
    out = oncall.IMPL[name](**args)
    if out in ("没有匹配的日志", "这段时间没有 GC 记录"):
        out += f"（查的是 {args['since']} 以后。现场最早的记录在 20:00；可以把 since 往前放，或换个关键字）"
    return out


def first_round(tools, runner):
    t0 = time.perf_counter()
    r = client.messages.create(model=MODEL, max_tokens=2048, system=oncall.SYSTEM, tools=tools,
                               messages=[{"role": "user", "content": oncall.QUESTION}])
    calls = [b for b in r.content if b.type == "tool_use"]
    shown = []
    for b in calls:
        out = runner(b.name, b.input) if b.name in oncall.IMPL else "没有这个工具"
        hit = "查到" if not out.startswith(("没有匹配", "这段时间没有")) else "落空"
        shown.append(f"{b.name}({b.input}) → {hit}")
    return time.perf_counter() - t0, shown


def compare(runs):
    only = sys.argv[sys.argv.index("--only") + 1] if "--only" in sys.argv else None
    for label, tools, runner in [("v1", V1, lambda n, a: oncall.IMPL[n](**a)), ("v2", V2, run_v2)]:
        if only and label != only:
            continue
        for i in range(runs):
            dt, shown = first_round(tools, runner)
            print(f"{label} 第 {i + 1} 次 [{dt:5.1f}s]：" + ("；".join(shown) or "没要工具"), flush=True)


def sizes():
    def tok(text):
        return client.messages.count_tokens(model=MODEL, messages=[{"role": "user", "content": text}]).input_tokens
    idle = ('"http-nio-8080-exec-{i}" #{n} daemon prio=5 os_prio=0 cpu=12.31ms elapsed=3601.22s tid=0x0000ffff{n:04x} nid={n} waiting on condition\n'
            "   java.lang.Thread.State: WAITING (parking)\n"
            "\tat jdk.internal.misc.Unsafe.park(java.base@17.0.13/Native Method)\n"
            "\t- parking to wait for  <0x00000000c1a2b3c4> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)\n"
            "\tat java.util.concurrent.locks.LockSupport.park(java.base@17.0.13/LockSupport.java:341)\n"
            "\tat org.apache.tomcat.util.threads.TaskQueue.take(TaskQueue.java:141)\n"
            "\tat java.lang.Thread.run(java.base@17.0.13/Thread.java:840)\n\n")
    raw_dump = "".join(idle.format(i=i, n=100 + i) for i in range(200))    # 200 个 Tomcat 线程的原样 jstack（示意）
    pairs = [("GC 日志", oncall.GC_LOG, oncall.parse_gc_log("20:00")),
             ("线程栈", raw_dump, oncall.THREAD_DUMP)]
    for name, raw, slim in pairs:
        print(f"{name}：原样 {len(raw)} 字 / {tok(raw)} token；精简 {len(slim)} 字 / {tok(slim)} token")


if __name__ == "__main__":
    if "--sizes" in sys.argv:
        sizes()
    else:
        n = int(sys.argv[sys.argv.index("--runs") + 1]) if "--runs" in sys.argv else 3
        compare(n)
