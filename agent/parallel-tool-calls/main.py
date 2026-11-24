"""并行工具调用：模型一轮要了 3 个工具，串行执行和并发执行各花多久；一个失败了怎么回。

    python main.py            # 假模型一轮要 3 个只读工具（各自模拟 0.8s / 1.5s / 0.4s 的延迟）
                              # 对比：串行、线程池并发、asyncio.gather；以及“一轮一个、分三轮要”
    python main.py --fail     # 其中一个工具抛异常：结果照样三个都回，失败的那个 is_error

结果的顺序不要紧（靠 tool_use_id 配对），但三个回执必须放进同一条 user 消息。
"""

import asyncio
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pano-04-triage-agent"))
import oncall                      # noqa: E402
from fake_model import FakeClient  # noqa: E402

DELAY = {"search_logs": 0.8, "parse_gc_log": 1.5, "thread_dump": 0.4}    # 模拟：查日志服务、读 GC 文件、jstack
FAIL = "--fail" in sys.argv
CALLS = [("search_logs", {"keyword": "WARN|ERROR", "since": "20:00"}), ("parse_gc_log", {"since": "20:00"}), ("thread_dump", {})]


def run_tool(block) -> dict:
    """同步版：失败也要回一条回执，不能丢。"""
    time.sleep(DELAY[block.name])
    try:
        if FAIL and block.name == "thread_dump":
            raise TimeoutError("jstack 20 秒没返回（目标进程可能卡在安全点）")
        return {"type": "tool_result", "tool_use_id": block.id, "content": oncall.IMPL[block.name](**block.input)}
    except Exception as e:  # noqa: BLE001
        return {"type": "tool_result", "tool_use_id": block.id, "is_error": True,
                "content": f"{type(e).__name__}: {e}。可以先用另外两个结果下判断，或稍后重试。"}


async def run_tool_async(block) -> dict:
    return await asyncio.to_thread(run_tool, block)          # 工具本身是阻塞的，放进线程


def one_round_blocks():
    r = FakeClient([CALLS, "结论：……"]).messages.create(model="fake", tools=oncall.TOOLS, messages=[])
    return [b for b in r.content if b.type == "tool_use"]


def timed(label, fn):
    t0 = time.perf_counter()
    results = fn()
    dt = time.perf_counter() - t0
    ids = [r["tool_use_id"][-3:] + ("(错)" if r.get("is_error") else "") for r in results]
    print(f"{label:28} {dt:4.2f}s  回执 {ids}")
    return results


def main():
    blocks = one_round_blocks()
    print(f"模型这一轮要了 {len(blocks)} 个工具：{[b.name for b in blocks]}，单个耗时 {list(DELAY.values())}s\n")
    timed("串行 for 循环", lambda: [run_tool(b) for b in blocks])
    timed("线程池并发 (max_workers=3)", lambda: list(ThreadPoolExecutor(max_workers=3).map(run_tool, blocks)))
    timed("asyncio.gather", lambda: asyncio.run(_gather(blocks)))
    results = timed("线程池并发 (max_workers=1)", lambda: list(ThreadPoolExecutor(max_workers=1).map(run_tool, blocks)))

    msg = {"role": "user", "content": results}               # 三个回执，一条消息
    print(f"\n送回：1 条 user 消息，{len(msg['content'])} 个 tool_result，顺序和 tool_use 一致")
    for r in results:
        print(f"   {r['tool_use_id']}  {'is_error ' if r.get('is_error') else ''}{r['content'][:46]}")

    # 不并行：模型一轮只要一个（disable_parallel_tool_use=True 的效果），要三轮
    model_s = 3.0                                             # 假设每轮模型 3 秒（连 Claude 的量级）
    serial_rounds = len(blocks) * model_s + sum(DELAY.values())
    one_round = model_s + max(DELAY.values())
    print(f"\n若每轮模型要 {model_s:.0f}s：分三轮要（每轮一个）≈ {serial_rounds:.1f}s；一轮要三个并发执行 ≈ {one_round:.1f}s")


async def _gather(blocks):
    return list(await asyncio.gather(*(run_tool_async(b) for b in blocks)))   # gather 保持传入顺序


if __name__ == "__main__":
    main()
