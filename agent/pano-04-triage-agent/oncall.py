"""值班助手 v1 的工具：4 个只读工具 + 1 个要人点头的工具。数据是 2026-11-08 20:16 那次 order-04 变慢的现场（假数据，前后一致）。

站 04 后面几个知识点的实验也 import 这个文件：
    sys.path.insert(0, str(Path(__file__).parent.parent / "pano-04-triage-agent")); import oncall
"""

import re

# ---------- 现场（假数据） ----------
APP_LOG = """\
2026-11-08 20:00:05.112 INFO  order-svc  [main] ExportController 上线：/orders/export（按月导出订单）
2026-11-08 20:09:41.630 WARN  order-svc  [http-nio-8080-exec-37] ExportService 导出 2026-10 订单 412,883 行，全部载入内存后写 Excel
2026-11-08 20:11:58.004 WARN  order-svc  [http-nio-8080-exec-12] ExportService 导出 2026-10 订单 412,883 行，全部载入内存后写 Excel
2026-11-08 20:12:45.090 WARN  order-svc  [http-nio-8080-exec-88] OrderController /orders/{id} 耗时 2104ms
2026-11-08 20:13:10.511 ERROR order-svc  [http-nio-8080-exec-91] OrderController /orders/{id} 耗时 2387ms，客户端已断开
2026-11-08 20:14:02.377 WARN  order-svc  [http-nio-8080-exec-40] ExportService 导出 2026-10 订单 412,883 行，全部载入内存后写 Excel
2026-11-08 20:15:30.020 WARN  order-svc  [http-nio-8080-exec-07] OrderController /orders/{id} 耗时 1951ms"""

GC_LOG = """\
[2026-11-08T20:09:58.101+0800][info][gc] GC(1209) Pause Young (Normal) (G1 Evacuation Pause) 388M->176M(1024M) 41.220ms
[2026-11-08T20:11:20.318+0800][info][gc] GC(1211) Pause Young (Concurrent Start) (G1 Humongous Allocation) 760M->690M(1024M) 188.940ms
[2026-11-08T20:12:03.512+0800][info][gc] GC(1213) Pause Young (Normal) (G1 Evacuation Pause) 412M->188M(1024M) 236.871ms
[2026-11-08T20:12:43.207+0800][info][gc] GC(1214) Pause Full (G1 Compaction Pause) 1010M->702M(1024M) 1873.412ms
[2026-11-08T20:13:10.033+0800][info][gc] GC(1215) Pause Young (Normal) (G1 Evacuation Pause) 905M->711M(1024M) 198.204ms
[2026-11-08T20:14:20.640+0800][info][gc] GC(1218) Pause Full (G1 Compaction Pause) 1018M->733M(1024M) 1920.008ms
[2026-11-08T20:15:31.902+0800][info][gc] GC(1221) Pause Full (G1 Compaction Pause) 1020M->741M(1024M) 1966.375ms"""

THREAD_DUMP = """\
"http-nio-8080-exec-40" RUNNABLE  at org.apache.poi.xssf.usermodel.XSSFSheet.createRow / at com.demo.order.ExportService.writeExcel(ExportService.java:88)
"http-nio-8080-exec-12" RUNNABLE  at java.util.ArrayList.grow / at com.demo.order.ExportService.loadAll(ExportService.java:51)
"http-nio-8080-exec-88" TIMED_WAITING at com.zaxxer.hikari.pool.HikariPool.getConnection (1 个)
其余 197 个 http-nio 线程 WAITING（空闲）"""

NOTES = [
    ("g1-zgc", "G1 和 ZGC 怎么回收", "Full GC 后老年代仍 70% 以上，说明有对象一直被引用（缓存、大列表），先 jmap -histo 看谁最多；Humongous Allocation 说明有超过半个 region 的大对象。"),
    ("jvm-memory-structure", "JVM 内存结构", "堆分新生代和老年代，大对象可能直接进老年代；导出类接口一次把几十万行读进 List 是常见的老年代杀手。"),
    ("three-highs-map", "三高知识地图", "接口超时先分清是自己慢还是下游慢：看线程栈停在哪、GC 停顿有多长。"),
]

# ---------- 工具定义：发给模型的“接口文档” ----------
TOOLS = [
    {"name": "search_logs",
     "description": "在应用日志里按关键字查 order-svc 某段时间的日志行。查报错、慢请求、上线记录时用。返回最多 20 行。",
     "input_schema": {"type": "object", "properties": {
         "keyword": {"type": "string", "description": "正则，例如 WARN|ERROR、耗时、Export"},
         "since": {"type": "string", "description": "开始时间 HH:MM，例如 20:00"}},
         "required": ["keyword"]}},
    {"name": "parse_gc_log",
     "description": "解析 order-svc 的 GC 日志，返回停顿次数、最长停顿、Full GC 次数、Full GC 后老年代剩多少。怀疑 GC 导致变慢时用。",
     "input_schema": {"type": "object", "properties": {
         "since": {"type": "string", "description": "开始时间 HH:MM"}}, "required": []}},
    {"name": "thread_dump",
     "description": "对 order-svc 做一次线程栈快照（jstack，停顿毫秒级），返回按状态归类的摘要。看线程卡在哪时用。",
     "input_schema": {"type": "object", "properties": {}, "required": []}},
    {"name": "search_notes",
     "description": "在值班知识库（学习笔记）里搜相关经验，返回最多 2 条摘要和页面名。",
     "input_schema": {"type": "object", "properties": {
         "query": {"type": "string", "description": "现象关键词，例如 Full GC 老年代"}}, "required": ["query"]}},
    {"name": "heap_dump",
     "description": "对 order-svc 做堆转储（jmap -dump:live，会触发一次 Full GC，应用停顿数秒）。会影响线上，调用前要值班人员确认。",
     "input_schema": {"type": "object", "properties": {
         "reason": {"type": "string", "description": "为什么必须现在 dump"}}, "required": ["reason"]}},
]
NEEDS_APPROVAL = {"heap_dump"}


def _after(line: str, since: str | None) -> bool:
    m = re.search(r"(\d\d:\d\d):\d\d", line)
    return not since or not m or m.group(1) >= since


def search_logs(keyword: str, since: str | None = None) -> str:
    rows = [l for l in APP_LOG.splitlines() if re.search(keyword, l) and _after(l, since)]
    return "\n".join(rows[:20]) or "没有匹配的日志"


def parse_gc_log(since: str | None = None) -> str:
    rows = [l for l in GC_LOG.splitlines() if _after(l, since)]
    if not rows:
        return "这段时间没有 GC 记录"
    pauses = [float(re.search(r"([\d.]+)ms$", l).group(1)) for l in rows]
    full = [l for l in rows if "Pause Full" in l]
    out = [f"{len(rows)} 次停顿，最长 {max(pauses):.0f}ms", f"Full GC {len(full)} 次"]
    if full:                                   # 只有真的有 Full GC 才报时长和回收后占用
        full_ms = [float(re.search(r"([\d.]+)ms$", l).group(1)) for l in full]
        after = [re.search(r"->(\d+)M\((\d+)M\)", l).groups() for l in full]
        olds = ", ".join(f"{int(a) * 100 // int(b)}%" for a, b in after)
        out[-1] += f"（每次约 {sum(full_ms) / len(full_ms) / 1000:.1f}s），Full GC 后堆仍占 {olds}"
    if any("Humongous" in l for l in rows):
        out.append("有 Humongous Allocation（大对象）")
    return "；".join(out)


def thread_dump() -> str:
    return THREAD_DUMP


def search_notes(query: str) -> str:
    words = [w for w in re.split(r"\s+", query.lower()) if w]
    hit = sorted(NOTES, key=lambda n: -sum(w in (n[1] + n[2]).lower() for w in words))[:2]
    return "\n".join(f"《{t}》（{p}）：{s}" for p, t, s in hit)


def heap_dump(reason: str) -> str:
    return "已生成 /dumps/order-svc-2026-11-08-2017.hprof（1.0 GB），dump 期间应用停顿 4.2s"


IMPL = {"search_logs": search_logs, "parse_gc_log": parse_gc_log, "thread_dump": thread_dump,
        "search_notes": search_notes, "heap_dump": heap_dump}

SYSTEM = """你是 order-svc 的值班排障助手。值班同学会问你某个告警的原因。
排查顺序（值班 SOP）：
1. 先看这段时间的报错和慢请求日志，以及有没有上线；
2. 怀疑 GC 时解析 GC 日志：Full GC 次数、停顿多长、回收后老年代剩多少；
3. 需要时看线程栈，确认线程卡在哪；
4. 到知识库里找相似经验；
5. 给结论：原因、证据（引用工具返回的原文数字）、建议的下一步。
只用工具拿到的事实，不要猜数字。能同时查的就在一轮里同时调用多个工具。"""

QUESTION = "20:16 告警：order-04 接口 P99 2.1 秒（阈值 800ms）。order-svc 为什么变慢了？"
