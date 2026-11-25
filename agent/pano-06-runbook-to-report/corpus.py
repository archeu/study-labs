"""站 06 的知识库语料：假数据，但前后一致，和站 04、05 的故事接得上。

生成到 data/（不进仓库）：
  runbook/order-svc-runbook-v3.2.pdf   12 页，页眉页脚、目录、两张表（一张跨页）、第 12 页是扫描件
  runbook/order-svc-runbook-v3.1.pdf   旧版：§2 的 P99 阈值是 1000 ms，§4.3 只是“建议”，没有 §4.4
  tickets/INC-20261108-01.docx         11-08 20:16 Full GC（按月导出 41 万行）
  tickets/INC-20261109-02.docx         11-09 10:42 P99 1.6 秒（Caffeine 本地缓存没设上限）
  notes/*.md                           三篇干净的学习笔记

用法：uv run python pano-06-runbook-to-report/corpus.py
"""
from pathlib import Path

import numpy as np
import pymupdf

HERE = Path(__file__).resolve().parent
DATA = HERE / "data"

CSS = """
* { font-family: sans-serif; font-size: 10.5pt; line-height: 1.5; }
h1 { font-size: 20pt; } h2 { font-size: 14pt; margin: 10px 0 4px; } h3 { font-size: 11.5pt; margin: 8px 0 2px; }
table { border-collapse: collapse; } td, th { border: 1px solid black; padding: 3px 5px; }
p, li { margin: 2px 0; }
"""

COVER = """
<h1>order-svc 运维手册</h1>
<p>版本 {ver} · 交易组 · {date} 修订</p>
<p>适用范围：order-svc 及其依赖的 order_db、Redis 集群、Kafka topic order-events。</p>
<h3>修订记录</h3>
<table><tr><th>版本</th><th>日期</th><th>改了什么</th></tr>
<tr><td>v3.0</td><td>2025-03-10</td><td>按 K8s 部署重写全书</td></tr>
<tr><td>v3.1</td><td>2025-06-18</td><td>新增 §3.4 Kafka 消费积压</td></tr>
{v32row}
</table>
"""
V32ROW = "<tr><td>v3.2</td><td>2026-11-01</td><td>§2 P99 阈值由 1000 ms 改为 800 ms；§4.3 本地缓存由“建议”改为“必须”；新增 §4.4 批量导出</td></tr>"

TOC = """
<h2>目录</h2>
<p>1 服务概览 .................................................. 3</p>
<p>2 告警与阈值 ................................................ 4</p>
<p>3 常见故障处置 .............................................. 5</p>
<p>&nbsp;&nbsp;3.1 Full GC 频繁 ........................................... 5</p>
<p>&nbsp;&nbsp;3.2 接口 P99 升高 .......................................... 6</p>
<p>&nbsp;&nbsp;3.3 数据库连接池耗尽 ....................................... 6</p>
<p>&nbsp;&nbsp;3.4 Kafka 消费积压 ......................................... 7</p>
<p>4 发布与变更规范 ............................................ 8</p>
<p>5 JVM 参数与 GC 日志 ........................................ 10</p>
<p>附录 C 降级开关与应急联系人（扫描件） ....................... 12</p>
"""

OVERVIEW = """
<h2>1 服务概览</h2>
<p>order-svc 负责下单、订单查询和订单导出，是交易链路的核心服务。上游是 gateway，下游调用 inventory-svc（扣库存）和 pay-svc（支付）。</p>
<h3>1.1 部署</h3>
<p>K8s 命名空间 trade，Deployment order-svc，6 个 Pod，每个 4 核 8 GB。JVM 堆 4 GB，垃圾收集器 G1。</p>
<p>依赖：MySQL 一主一从（库名 order_db，从库只读）；Redis 集群 3 主 3 从；Kafka topic order-events，12 个分区。</p>
<h3>1.2 关键接口</h3>
<p>order-01 下单 POST /orders；order-02 订单列表 GET /orders；order-04 订单详情 GET /orders/{id}/detail，带一层本地缓存（Caffeine），未命中再查 Redis 和 MySQL；order-07 订单导出 POST /orders/export。</p>
<p>平时 order-04 的 P99 约 300 ms，晚高峰 19:00–22:00 的 QPS 是白天的 3 倍。</p>
"""

ALERTS = """
<h2>2 告警与阈值</h2>
<p>阈值按 2026-10 的压测结果定。P2 在值班群通知，P1 同时电话呼叫值班人。</p>
<table><tr><th>指标</th><th>阈值</th><th>级别</th></tr>
<tr><td>接口 P99（order-04）</td><td>&gt; {p99} ms 持续 3 分钟</td><td>P2</td></tr>
<tr><td>接口错误率</td><td>&gt; 1% 持续 2 分钟</td><td>P1</td></tr>
<tr><td>Old 区占用</td><td>&gt; 85% 持续 5 分钟</td><td>P2</td></tr>
<tr><td>Full GC 次数</td><td>10 分钟内 &gt; 3 次</td><td>P1</td></tr>
<tr><td>连接池等待线程</td><td>&gt; 10 持续 1 分钟</td><td>P2</td></tr>
<tr><td>Kafka 消费积压</td><td>lag &gt; 100000</td><td>P2</td></tr>
</table>
<p>告警消息里带 Grafana 面板链接和最近一次发布的版本号。</p>
"""

FULLGC = """
<h2>3 常见故障处置</h2>
<h3>3.1 Full GC 频繁</h3>
<p>现象：Old 区占用持续上涨，Full GC 次数告警，接口 P99 跟着升高。</p>
<p>第一步，看 GC 日志（位置见 §5.2），确认 Old 区回收后是否还在涨：回收后还涨，基本是有对象一直被引用，多半是缓存或大批量查询。</p>
<p>第二步，看最近一次发布带了什么：新加的缓存、导出、批处理最可疑。</p>
<p>第三步，用 jmap -histo:live 看占用最多的类。不要直接做 heap dump：堆 4 GB 的 dump 会让 Pod 停顿 8 秒以上。</p>
<p>晚高峰 19:00–22:00 不做 heap dump；必须做时先把这个 Pod 摘流量。</p>
<p>止血：能关掉可疑功能的，先用降级开关关掉（开关表见附录 C）；关不掉的，按 §4.2 回滚。</p>
"""

P99 = """
<h3>3.2 接口 P99 升高</h3>
<p>排查顺序：最近发布 → GC → 慢 SQL → 下游。八成的 P99 升高能在前两步找到原因。</p>
<p>看下游时，对比 inventory-svc、pay-svc 的 P99 是否同时升高；同时升高说明是共享的东西出了问题，比如数据库或网络。</p>
<h3>3.3 数据库连接池耗尽</h3>
<p>order-svc 用 HikariCP，maximumPoolSize=20，connectionTimeout=3000 ms。等待线程告警时，先看是不是有慢 SQL 占着连接不放。</p>
<p>不要直接调大连接池：6 个 Pod × 20 已经是 120 个连接，从库的 max_connections 是 300。</p>
"""

KAFKA = """
<h3>3.4 Kafka 消费积压</h3>
<p>order-events 有 12 个分区，消费组 order-svc-consumer 最多 12 个消费者同时干活，多开 Pod 也没用。</p>
<p>积压时先看是不是某个分区卡住：一个分区的 lag 远大于其他分区，多半是一条消息处理一直失败、在重试。</p>
<p>处理失败超过 3 次的消息进死信 topic order-events-dlq，第二天白天人工处理。</p>
"""

RELEASE = """
<h2>4 发布与变更规范</h2>
<h3>4.1 灰度</h3>
<p>先发 1 个 Pod，观察 15 分钟，看 P99、错误率、Old 区占用三项；没问题再全量。晚高峰 19:00–22:00 不发布。</p>
<h3>4.2 回滚</h3>
<p>发布后 30 分钟内，P99 翻倍或错误率 &gt; 1%，不用找原因，立即回滚：</p>
<p>kubectl rollout undo deploy/order-svc -n trade</p>
<p>超过 30 分钟才发现的问题，先评估回滚会不会把其他已上线的修复一起撤掉；能用降级开关止血的优先用开关。</p>
"""

CACHE_V32 = """
<h3>4.3 本地缓存规范</h3>
<p>本地缓存（Caffeine、Guava Cache）必须同时设置容量上限 maximumSize（或 maximumWeight）和过期时间 expireAfterWrite，评审时缺任何一项直接驳回。</p>
<p>容量按“单条大小 × 最大条数 ≤ 堆的 10%”估算：4 GB 堆就是不超过 400 MB。</p>
<p>必须打开 recordStats()，把命中率和淘汰次数上报到监控；命中率低于 50% 的本地缓存，说明它没起作用，应该删掉。</p>
<h3>4.4 批量导出</h3>
<p>导出必须分批，每批不超过 5000 行，查只读从库，不查主库。</p>
<p>超过 10 万行的导出走异步任务，放在夜间 01:00–05:00 执行，结果发到用户邮箱。</p>
"""
CACHE_V31 = """
<h3>4.3 本地缓存规范</h3>
<p>本地缓存（Caffeine、Guava Cache）建议设置容量上限，过期时间按业务需要设置。</p>
<p>缓存的数据要能从 Redis 或数据库重建，不要把本地缓存当成唯一的数据来源。</p>
"""

JVM1 = """
<h2>5 JVM 参数与 GC 日志</h2>
<h3>5.1 JVM 参数</h3>
<p>所有 Pod 用同一套参数，改参数要走变更评审。</p>
<table><tr><th>参数</th><th>值</th><th>为什么</th></tr>
<tr><td>-Xms / -Xmx</td><td>4g / 4g</td><td>两者相等，避免堆来回伸缩</td></tr>
<tr><td>-XX:+UseG1GC</td><td>开</td><td>堆 4 GB，停顿要可控</td></tr>
<tr><td>-XX:MaxGCPauseMillis</td><td>200</td><td>G1 的停顿目标，不是保证</td></tr>
<tr><td>-XX:InitiatingHeapOccupancyPercent</td><td>45</td><td>堆占用到 45% 开始并发标记</td></tr>
<tr><td>-XX:+HeapDumpOnOutOfMemoryError</td><td>开</td><td>OOM 时自动留现场</td></tr>
"""
JVM2 = """
<table><tr><td>-XX:HeapDumpPath</td><td>/data/dump</td><td>挂了持久卷，Pod 重启不丢</td></tr>
<tr><td>-Xlog:gc*</td><td>见 §5.2</td><td>GC 日志</td></tr>
<tr><td>-XX:MaxMetaspaceSize</td><td>512m</td><td>防止类加载泄漏吃满内存</td></tr>
</table>
<h3>5.2 GC 日志</h3>
<p>位置：/data/logs/order-svc/gc.log，按 50 MB 滚动，保留 7 天。参数：-Xlog:gc*:file=/data/logs/order-svc/gc.log:time,uptime:filecount=10,filesize=50m</p>
<p>看 Old 区要看每次 Mixed GC 和 Full GC 之后的占用，不看回收前的峰值。</p>
"""

APPENDIX_C = """
<h2>附录 C 降级开关与应急联系人</h2>
<p>（2024 年纸质应急预案扫描件，内容仍有效）</p>
<table><tr><th>开关</th><th>关掉后</th></tr>
<tr><td>order.export.enabled</td><td>停止订单导出，已在跑的任务会跑完</td></tr>
<tr><td>order.cache.local.enabled</td><td>不用本地缓存，订单详情直接查 Redis</td></tr>
<tr><td>order.recommend.enabled</td><td>下单页不展示推荐商品</td></tr>
</table>
<p>开关在配置中心 trade/order-svc 下修改，1 分钟内生效，不用重启。</p>
<p>应急联系：交易组值班群 #trade-oncall；DBA 值班群 #dba-oncall。</p>
"""


def runbook_pages(ver):
    v32 = ver == "v3.2"
    date = "2026-11-01" if v32 else "2025-06-18"
    return [
        COVER.format(ver=ver, date=date, v32row=V32ROW if v32 else ""),
        TOC, OVERVIEW, ALERTS.format(p99=800 if v32 else 1000),
        FULLGC, P99, KAFKA, RELEASE,
        CACHE_V32 if v32 else CACHE_V31,
        JVM1, JVM2, APPENDIX_C,
    ]


def scanned(html):
    """把一页渲染成图片，加一点倾斜和噪点，再当成扫描件放回 PDF：这一页没有文字层。"""
    import cv2
    tmp = pymupdf.open()
    pg = tmp.new_page()
    pg.insert_htmlbox(pymupdf.Rect(60, 60, 540, 780), html, css=CSS)
    pix = pg.get_pixmap(dpi=110, colorspace=pymupdf.csGRAY)
    img = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.h, pix.w).copy()
    rot = cv2.getRotationMatrix2D((pix.w / 2, pix.h / 2), 1.2, 1.0)
    img = cv2.warpAffine(img, rot, (pix.w, pix.h), borderValue=255)
    rng = np.random.default_rng(12)
    img = np.clip(img.astype(int) - (rng.random(img.shape) < 0.015) * 120 + 18, 0, 255).astype(np.uint8)
    ok, png = cv2.imencode(".png", img)
    return png.tobytes()


def make_runbook(ver, out):
    pages = runbook_pages(ver)
    doc = pymupdf.open()
    n = len(pages)
    for i, html in enumerate(pages, start=1):
        pg = doc.new_page()
        if i == n:  # 附录 C：扫描件
            pg.insert_image(pg.rect, stream=scanned(html))
            continue
        pg.insert_htmlbox(pymupdf.Rect(60, 64, 540, 790), html, css=CSS)
        if i > 1:  # 页眉页脚：每页都有，是要清洗掉的噪声
            pg.insert_text((60, 36), f"order-svc 运维手册 {ver} · 交易组 · 内部资料", fontname="china-s", fontsize=8)
            pg.insert_text((270, 815), f"第 {i} 页 / 共 {n} 页", fontname="china-s", fontsize=8)
    doc.set_metadata({"title": f"order-svc 运维手册 {ver}", "author": "交易组"})
    doc.subset_fonts()  # 不做子集化，每页都嵌一整套中文字体，一份 PDF 40 MB
    doc.save(out, garbage=4, deflate=True)


TICKETS = {
    "INC-20261108-01": {
        "title": "order-svc Full GC 频繁，order-04 P99 2.1 秒",
        "info": [("级别", "P1"), ("开始", "2026-11-08 20:16"), ("恢复", "2026-11-08 21:05"), ("处理人", "交易组值班")],
        "timeline": [("20:00", "发布 v2.3.0，新增“按月导出订单”"), ("20:16", "告警：order-04 P99 2.1 秒，Full GC 10 分钟 5 次"),
                     ("20:31", "GC 日志显示 Old 区回收后仍在涨"), ("20:40", "jmap -histo 发现 41 万个 OrderRow 对象"),
                     ("20:48", "关闭开关 order.export.enabled"), ("21:05", "P99 回到 320 ms")],
        "cause": "按月导出一次把 41 万行订单读进内存，Old 区被占满，频繁 Full GC。",
        "fix": "导出改成每批 5000 行，随 v2.3.1 发布；运维手册 v3.2 新增 §4.4 批量导出。",
    },
    "INC-20261109-02": {
        "title": "order-svc P99 1.6 秒，Old 区持续上涨",
        "info": [("级别", "P2"), ("开始", "2026-11-09 10:42"), ("恢复", "2026-11-09 11:12"), ("处理人", "交易组值班")],
        "timeline": [("10:00", "发布 v2.3.1：分批导出修复，同时新增订单详情本地缓存 OrderDetailCache"),
                     ("10:42", "告警：order-04 P99 1.6 秒"), ("10:50", "Old 区占用 88%，回收后仍在涨"),
                     ("10:58", "jmap -histo：OrderDetail 对象 120 万个，约 2.1 GB"),
                     ("11:05", "关闭开关 order.cache.local.enabled，订单详情改查 Redis"), ("11:12", "P99 回到 310 ms，Old 区开始回落")],
        "cause": "OrderDetailCache 用 Caffeine 实现，没有设置 maximumSize，也没有过期时间，订单详情只进不出。",
        "fix": "补上 maximumSize(50000) 和 expireAfterWrite(10 分钟)，计划随 v2.3.2 发布；发布前按 §4.3 估算容量。",
    },
}


def make_ticket(tid, t, out):
    from docx import Document
    d = Document()
    d.add_heading(f"{tid} {t['title']}", level=1)
    tb = d.add_table(rows=0, cols=2)
    tb.style = "Table Grid"
    for k, v in t["info"]:
        r = tb.add_row().cells
        r[0].text, r[1].text = k, v
    d.add_heading("时间线", level=2)
    tb = d.add_table(rows=1, cols=2)
    tb.style = "Table Grid"
    tb.rows[0].cells[0].text, tb.rows[0].cells[1].text = "时间", "事件"
    for k, v in t["timeline"]:
        r = tb.add_row().cells
        r[0].text, r[1].text = k, v
    d.add_heading("根因", level=2)
    d.add_paragraph(t["cause"])
    d.add_heading("修复和改进", level=2)
    d.add_paragraph(t["fix"])
    d.save(out)


NOTES = {
    "caffeine-local-cache.md": """# Caffeine 本地缓存：上限和过期

Caffeine 是 JVM 进程内的缓存，数据放在堆里。不设上限时，它就是一个只进不出的 ConcurrentHashMap。

## 容量上限

maximumSize(n) 按条数限制；maximumWeight(w) 配合 weigher 按大小限制。超过上限时，Caffeine 用 W-TinyLFU 决定淘汰谁：既看最近有没有被访问，也看历史访问频率。

## 过期

expireAfterWrite 从写入开始计时；expireAfterAccess 从最后一次访问开始计时。只设过期不设上限，突发流量下条数仍可能在过期前涨爆。

## 监控

recordStats() 打开后，stats() 能拿到命中率、淘汰次数、加载耗时。
""",
    "g1-old-gen.md": """# G1 的 Old 区为什么会一直涨

G1 把堆分成很多 Region。存活得久的对象会被晋升到 Old Region。

Old 区占用超过 InitiatingHeapOccupancyPercent（默认 45%）时，G1 开始并发标记，之后用 Mixed GC 回收一部分 Old Region。

如果对象一直被引用（比如放进了没有上限的缓存），Mixed GC 回收不掉它们，Old 区占用会一路涨到触发 Full GC。这时看 GC 日志，每次回收之后的占用在抬高，这条线叫“回收后水位”。
""",
    "hikari-pool.md": """# HikariCP 连接池耗尽怎么看

连接池满了以后，新请求在 getConnection() 上等待，最多等 connectionTimeout，超时抛 SQLTransientConnectionException。

先看是谁占着连接：慢 SQL、事务里调了远程接口、忘了关连接。调大 maximumPoolSize 往往只是把压力转给数据库。
""",
}


def build():
    (DATA / "runbook").mkdir(parents=True, exist_ok=True)
    (DATA / "tickets").mkdir(parents=True, exist_ok=True)
    (DATA / "notes").mkdir(parents=True, exist_ok=True)
    for ver in ("v3.1", "v3.2"):
        make_runbook(ver, DATA / "runbook" / f"order-svc-runbook-{ver}.pdf")
    for tid, t in TICKETS.items():
        make_ticket(tid, t, DATA / "tickets" / f"{tid}.docx")
    for name, text in NOTES.items():
        (DATA / "notes" / name).write_text(text, encoding="utf-8")
    files = sorted(p for p in DATA.rglob("*") if p.is_file())
    for p in files:
        print(f"{p.relative_to(DATA)}  {p.stat().st_size // 1024} KB")


if __name__ == "__main__":
    build()
