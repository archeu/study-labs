"""2026-11-09 10:42–10:50 这次会话的记录（全景第 1～7 站，压缩过的样子，假数据）。第 8 站从这里抽记忆。"""

TRANSCRIPT = """\
[10:42 值班的你] 10:42 告警：order-svc P99 1.6 秒。是不是昨天那个问题？
[10:43 工具 parse_gc_log] 要点：10:00–10:42 Full GC 14 次，最长停顿 2311 ms；Full GC 后老年代仍占 92%。
[10:44 工具 search_logs] 要点：10:00 发布 v2.3.1（ExportService 改为分批导出；新增 OrderSnapshotCache，Caffeine）。
[10:47 子 agent 读堆直方图] com.trade.order.OrderSnapshot 9384211 个实例，2252210640 字节（约 2.1 GB），来自 OrderSnapshotCache.load。
[10:49 助手结论] 症状和昨天一样是 Full GC，但原因不同。昨天的导出问题已在 10:00 随 v2.3.1 改成分批导出；今天占堆的是同一版本新加的 Caffeine 缓存 OrderSnapshotCache，没设 maximumSize，OrderSnapshot 占约 2.1 GB。建议加 maximumSize 后热修。
[10:50 值班的你] 好，让小组加上 maximumSize(50_000) 热修。另外我下午 3 点前在开会，有事找备班。
[10:50 值班的你] 以后 order-svc 再 Full GC，先看堆里最大的类是不是缓存对象。"""

NOW = "2026-11-09 10:50"
