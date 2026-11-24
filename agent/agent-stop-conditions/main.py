"""停止条件：步数、预算、超时。用照剧本出牌的假模型，制造一个“停不下来”的 agent，看哪道闸先拦住它。

    python main.py              # 四个场景各跑一遍
    python main.py --no-limits  # 去掉全部上限（只保留一个 60 轮的安全绳），看会跑多久

场景：
  A 正常：3 轮查完，第 4 轮答完                   → end_turn
  B 死磕：search_logs 永远“没有匹配”，模型一直换关键字   → 步数上限
  C 话痨：每轮都要 thread_dump，结果很长            → token 预算
  D 慢工具：每次调用 2 秒                        → 墙钟超时
"""

import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pano-04-triage-agent"))
import oncall                      # noqa: E402  站 04 共用的工具和现场数据
from fake_model import FakeClient  # noqa: E402


@dataclass
class Limits:
    max_steps: int = 15             # 最多调几次模型
    max_tokens: int = 30_000        # 输入 + 输出累计
    max_seconds: float = 8.0        # 整次排查的墙钟时间（演示用，调小了）


@dataclass
class Outcome:
    reason: str
    steps: int
    tokens: int
    seconds: float
    log: list = field(default_factory=list)


def run(client, tools_impl, limits: Limits) -> Outcome:
    messages = [{"role": "user", "content": oncall.QUESTION}]
    used, t0, step = 0, time.perf_counter(), 0
    while True:
        # ---- 每次调模型之前检查三道闸 ----
        if step >= limits.max_steps:
            return Outcome("步数上限", step, used, time.perf_counter() - t0)
        if used >= limits.max_tokens:
            return Outcome("token 预算", step, used, time.perf_counter() - t0)
        if time.perf_counter() - t0 >= limits.max_seconds:
            return Outcome("墙钟超时", step, used, time.perf_counter() - t0)

        step += 1
        resp = client.messages.create(model="fake", max_tokens=1024, system=oncall.SYSTEM,
                                      tools=oncall.TOOLS, messages=messages)
        used += resp.usage.input_tokens + resp.usage.output_tokens
        messages.append({"role": "assistant", "content": [b.model_dump(exclude_none=True) for b in resp.content]})
        if resp.stop_reason == "end_turn":
            return Outcome("end_turn（正常答完）", step, used, time.perf_counter() - t0)
        if resp.stop_reason == "max_tokens":
            return Outcome("max_tokens（单次回答被截断）", step, used, time.perf_counter() - t0)
        results = []
        for b in resp.content:
            if b.type == "tool_use":
                out = tools_impl[b.name](**b.input)
                results.append({"type": "tool_result", "tool_use_id": b.id, "content": out})
        messages.append({"role": "user", "content": results})


def slow(fn, seconds):
    def wrapped(**kw):
        time.sleep(seconds)
        return fn(**kw)
    return wrapped


KEYWORDS = ["OOM", "OutOfMemory", "Timeout", "Deadlock", "Refused", "Exception", "panic", "killed"]


def scenarios():
    yield "A 正常", FakeClient([[("search_logs", {"keyword": "WARN|ERROR", "since": "20:00"}), ("parse_gc_log", {"since": "20:00"})],
                               [("thread_dump", {})], [("search_notes", {"query": "Full GC 老年代"})],
                               "结论：20:00 上线的导出把 41 万行读进内存，Full GC 每次约 1.9s。"]), oncall.IMPL
    # B：每轮换一个查不到的关键字；剧本用完后一直重复最后一张
    yield "B 死磕", FakeClient([[("search_logs", {"keyword": k})] for k in KEYWORDS], repeat_last=True), oncall.IMPL
    yield "C 话痨", FakeClient([[("thread_dump", {})]], repeat_last=True), {**oncall.IMPL, "thread_dump": lambda: oncall.THREAD_DUMP * 12}
    yield "D 慢工具", FakeClient([[("parse_gc_log", {})]], repeat_last=True), {**oncall.IMPL, "parse_gc_log": slow(oncall.parse_gc_log, 2.0)}


def main():
    limits = Limits(max_steps=60, max_tokens=10**9, max_seconds=10**9) if "--no-limits" in sys.argv else Limits()
    print(f"上限：步数 {limits.max_steps}，token {limits.max_tokens}，时长 {limits.max_seconds}s")
    for name, client, impl in scenarios():
        if "--no-limits" in sys.argv and name.startswith("D"):
            print(f"{name:6} 跳过（不设超时会跑 2 分钟）")
            continue
        o = run(client, impl, limits)
        print(f"{name:6} 停在：{o.reason:18} 第 {o.steps:2} 轮  累计 {o.tokens:6} token  {o.seconds:5.1f}s")


if __name__ == "__main__":
    main()
