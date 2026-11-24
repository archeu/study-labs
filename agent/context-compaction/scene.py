"""全景“第二天再问同一个服务”第 4～6 站的现场：2026-11-09 10:42，order-svc 又慢了（假数据，前后一致）。

真相：10:00 发布 v2.3.1，分批导出已上线；同一版本新加的 Caffeine 缓存 OrderSnapshotCache 没设 maximumSize。
10:00–10:42 一共 14 次 Full GC，最长一次 2311 ms，Full GC 后老年代仍占 92%。

约定：每个工具结果的第一行是“要点：……”，由工具自己写。清理旧结果时可以只留这一行。
"""

import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "context-engineering"))
from context_builder import MEMORIES, build_request  # noqa: E402

TRUTH = {"full_gc": "14", "max_pause": "2311", "old_after": "92"}
QUESTION = "10:42 告警：order-svc P99 1.6 秒。是不是昨天那个问题？"


def gc_log() -> str:
    rnd = random.Random(1109)
    full_at = sorted(rnd.sample(range(5, 80), 14))
    pauses = [rnd.randint(1500, 2290) for _ in full_at]
    pauses[9] = 2311                                     # 最长那一次
    lines, gc_id = [], 3100
    for i in range(80):
        s = (i * 31) % 60
        ts = f"2026-11-09T10:{(i * 31) // 60:02d}:{s:02d}.{rnd.randint(100, 999)}+0800"
        if i in full_at:
            p = pauses[full_at.index(i)]
            lines.append(f"[{ts}][info][gc] GC({gc_id}) Pause Full (G1 Compaction Pause) 3958M->3768M(4096M) {p}.{rnd.randint(100, 999)}ms")
        else:
            a = rnd.randint(2300, 3900)
            lines.append(f"[{ts}][info][gc] GC({gc_id}) Pause Young (Normal) (G1 Evacuation Pause) {a}M->{a - rnd.randint(200, 600)}M(4096M) {rnd.randint(30, 260)}.{rnd.randint(100, 999)}ms")
        gc_id += 1
    gist = "要点：10:00–10:42 共 80 次 GC，其中 Full GC 14 次，最长停顿 2311 ms；Full GC 后老年代仍占 92%（3768M/4096M）。"
    return gist + "\n" + "\n".join(lines)


def app_log() -> str:
    rnd = random.Random(42)
    lines = ["2026-11-09 10:00:03.118 INFO  order-svc [main] 发布 v2.3.1：ExportService 改为分批导出（每批 5000 行）；新增 OrderSnapshotCache（Caffeine）"]
    for i in range(39):
        t = f"10:{1 + i:02d}:{rnd.randint(10, 59)}.{rnd.randint(100, 999)}"
        if i % 3 == 0:
            lines.append(f"2026-11-09 {t} INFO  order-svc [http-nio-8080-exec-{rnd.randint(1, 200)}] ExportService 分批导出 第 {rnd.randint(1, 80)} 批 5000 行，耗时 {rnd.randint(80, 300)}ms")
        else:
            lines.append(f"2026-11-09 {t} WARN  order-svc [http-nio-8080-exec-{rnd.randint(1, 200)}] OrderController /orders/{{id}} 耗时 {rnd.randint(1200, 2600)}ms")
    gist = "要点：10:00 发布 v2.3.1（分批导出 + 新增 OrderSnapshotCache）；之后 /orders/{id} 慢请求 26 条（1.2–2.6 秒），导出已是每批 5000 行。"
    return gist + "\n" + "\n".join(lines)


def thread_dump() -> str:
    rows = ['"http-nio-8080-exec-17" RUNNABLE  at com.github.benmanes.caffeine.cache.BoundedLocalCache.put / at com.trade.cache.OrderSnapshotCache.put(OrderSnapshotCache.java:41)',
            '"http-nio-8080-exec-88" RUNNABLE  at com.trade.order.OrderSnapshot.<init>(OrderSnapshot.java:23) / at com.trade.cache.OrderSnapshotCache.load(OrderSnapshotCache.java:57)']
    rows += [f'"http-nio-8080-exec-{n}" TIMED_WAITING  at jdk.internal.misc.Unsafe.park / at java.util.concurrent.locks.LockSupport.parkNanos' for n in range(100, 126)]
    gist = "要点：2 个线程在 OrderSnapshotCache 里构造/写入快照，没有线程卡在导出；其余 26 个 http 线程空闲等待。"
    return gist + "\n" + "\n".join(rows)


def heap_histo(n: int = 250) -> str:
    """jmap -histo:live 的样子，按占用字节从大到小。真实的有几万行，这里取前 n 行。"""
    rnd = random.Random(7)
    top = [("com.trade.order.OrderSnapshot", 9_384_211, 2_252_210_640), ("[B", 4_102_557, 611_280_904),
           ("java.lang.String", 9_380_002, 225_120_048), ("com.github.benmanes.caffeine.cache.PSMS", 9_384_211, 300_294_752),
           ("java.math.BigDecimal", 4_210_330, 134_730_560)]
    lines = [" num     #instances         #bytes  class name (module)", "-------------------------------------------------------"]
    rows = sorted(top, key=lambda r: -r[2])
    size = 90_000_000
    for i in range(n - len(rows)):
        size = int(size * rnd.uniform(0.93, 0.99))
        rows.append((f"com.trade.{rnd.choice(['order', 'pay', 'common', 'export'])}.Type{i:04d}", rnd.randint(10, 90_000), size))
    for i, (cls, inst, b) in enumerate(rows, 1):
        lines.append(f"{i:4d}: {inst:14d} {b:14d}  {cls}")
    return "\n".join(lines)


def tool_round(i: int, name: str, args: dict, text: str, say: str):
    tid = f"toolu_{i:02d}"
    return [{"role": "assistant", "content": [{"type": "text", "text": say},
                                              {"type": "tool_use", "id": tid, "name": name, "input": args}]},
            {"role": "user", "content": [{"type": "tool_result", "tool_use_id": tid, "content": text}]}]


def history() -> dict:
    """第 4 轮之后的完整请求：第 1 次请求 + 3 轮工具来回。"""
    req = build_request(QUESTION, MEMORIES, now="2026-11-09 10:42", layout="good")
    req["messages"] += tool_round(1, "parse_gc_log", {"since": "10:00"}, gc_log(), "先看今天的 GC 情况。")
    req["messages"] += tool_round(2, "search_logs", {"keyword": "WARN|发布|Export", "since": "10:00"}, app_log(), "Full GC 很频繁。看看 10:00 前后发生了什么。")
    req["messages"] += tool_round(3, "thread_dump", {}, thread_dump(), "导出已经是分批的了。看线程在忙什么。")
    return req
