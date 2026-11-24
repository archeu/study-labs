"""20 个评测用例：每个是一串写入事件 + 一个问题 + 判定规则（取回的文本里必须有 / 不能有什么）。假数据，和全景一致。

事件：
  ("write", 日期, kind, team, key, text, importance, extra)   extra 可含 expires、source、status
  ("status", 日期, 文本里的标记, 新状态)                        情景记忆的状态变化（待发布 → 已发布）
  ("revoke", 日期, 文本里的标记)                                值班人员说“这条不对，忘掉”
"""

T, R = "trade", "risk"
D = "2026-11-09 10:42"   # 默认提问时间


def w(date, kind, key, text, imp=5, team=T, **extra):
    return ("write", date, kind, team, key, text, imp, extra)


BG = [  # 每个用例都带上的背景记忆：几条和问题无关、但会来抢名额的
    w("2026-11-06", "episodic", "order-svc", "11-06 order-svc 接口偶尔 800ms：下游 user-svc 冷启动，加了预热。", 4),
    w("2026-10-11", "episodic", "order-svc", "10-11 order-svc P99 升到 1.2 秒：一次慢查询没走索引，加联合索引。", 5),
    w("2026-11-05", "episodic", "cart-svc", "11-05 cart-svc 响应变慢：Redis 大 key（单个购物车 8 MB），拆分后恢复。", 5),
    w("2026-10-21", "episodic", "payment-svc", "10-21 payment-svc 接口超时：HikariCP 连接池打满，慢 SQL 占着连接。", 6),
    w("2026-11-08", "episodic", "risk-svc", "11-08 risk-svc Full GC：规则引擎一次加载全部黑名单，改布隆过滤器。", 8, team=R),
]

CASES = [
    # ---------- 该记的记住了 ----------
    dict(id=1, cat="记住", q="10:42 告警：order-svc P99 1.6 秒。是不是昨天那个问题？", events=[
        w("2026-11-08", "episodic", "order-svc", "11-08 20:16 order-svc Full GC：按月导出一次读 41 万行进内存。修复：改分批导出。", 8, status="待发布")],
         must=["41 万行"]),
    dict(id=2, cat="记住", q="order-svc 现在 20:30，能不能做一次 heap dump？", at="2026-11-09 20:30", events=[
        w("2026-11-08", "procedural", "*", "晚高峰 19:00–22:00 不做 heap dump。", 9)], must=["不做 heap dump"]),
    dict(id=3, cat="记住", q="order-svc 的堆是多大的？", events=[
        w("2026-11-02", "semantic", "order-svc/jvm", "order-svc：堆 4 GB，G1，6 个 Pod。", 5)], must=["4 GB"]),
    dict(id=4, cat="记住", q="order-svc 这次发布要改表结构，有什么要注意的？", events=[
        w("2026-08-16", "episodic", "order-svc", "08-16 order-svc P0：发布时迁移脚本锁表，全量下单失败 40 分钟。此后 DDL 必须评审。", 10)],
         must=["锁表"]),
    dict(id=5, cat="记住", q="payment-svc 要重启一下，有什么要求？", events=[
        w("2026-10-02", "procedural", "payment-svc", "重启 payment-svc 之前必须先摘流量，并在 #trade-oncall 说一声。", 8)], must=["摘流量"]),
    dict(id=6, cat="记住", q="cart-svc 又变慢了，以前遇到过吗？", events=[], must=["大 key"]),
    dict(id=7, cat="记住", q="payment-svc 连接池配了多少？", events=[
        w("2026-10-21", "semantic", "payment-svc/db", "payment-svc：HikariCP maximumPoolSize=10，MySQL 8.0 主从。", 5)], must=["maximumPoolSize=10"]),

    # ---------- 该忘的忘了（以及不该记的没记） ----------
    dict(id=8, cat="忘掉", q="今天下午谁是备班？有事找谁？", at="2026-11-10 14:00", events=[
        w("2026-11-09", "temporary", "值班", "11-09 值班人员说：今天下午 3 点前在开会，有事找备班。", 1, expires="2026-11-09 15:00")],
         must_not=["3 点前在开会"]),
    dict(id=9, cat="忘掉", q="Full GC 一般怎么处理？以前的经验呢？", events=[], must_not=["risk-svc"]),
    dict(id=10, cat="忘掉", q="order-svc 昨天的原因是网络吗？", events=[
        w("2026-11-08", "episodic", "order-svc", "11-08 order-svc 变慢可能是网络抖动（助手推断，未证实）。", 3, source="model")],
         must_not=["网络抖动"]),
    dict(id=11, cat="忘掉", q="order-svc 昨天 Full GC 的原因是什么？", events=[
        w("2026-11-08", "episodic", "order-svc", "11-08 order-svc Full GC 原因：日志框架同步写大对象。", 6),
        ("revoke", "2026-11-08", "日志框架同步写大对象"),
        w("2026-11-08", "episodic", "order-svc", "11-08 order-svc Full GC 原因：按月导出一次读 41 万行进内存。", 8)],
         must=["41 万行"], must_not=["日志框架同步写大对象"]),
    dict(id=12, cat="忘掉", q="现在能重启 order-svc 吗？有什么规矩？", events=[
        w("2026-10-01", "procedural", "risk-svc", "重启 risk-svc 前必须通知风控值班。", 8, team=R)], must_not=["风控值班"]),

    # ---------- 新旧冲突时用新的（以及不该合并的别合并） ----------
    dict(id=13, cat="冲突", q="order-svc 的堆现在多大？", events=[
        w("2026-11-02", "semantic", "order-svc/jvm", "order-svc：堆 4 GB，G1，6 个 Pod。", 5),
        w("2026-11-09", "semantic", "order-svc/jvm", "order-svc：堆 8 GB，G1，6 个 Pod（11-09 扩容）。", 5)],
         must=["8 GB"], must_not=["堆 4 GB"]),
    dict(id=14, cat="冲突", q="order-svc 的按月导出那个问题修好了吗？", events=[
        w("2026-11-08", "episodic", "order-svc", "11-08 20:16 order-svc Full GC：按月导出一次读 41 万行进内存。修复：改分批导出。", 8, status="待发布"),
        ("status", "2026-11-09", "按月导出一次读 41 万行", "已随 v2.3.1 发布")],
         must=["已随 v2.3.1 发布"], must_not=["待发布"]),
    dict(id=15, cat="冲突", q="order-svc 出事了在哪个群里说？", events=[
        w("2026-09-01", "semantic", "order-svc/owner", "order-svc 归交易组，值班群 #trade-oncall。", 4),
        w("2026-11-03", "semantic", "order-svc/owner", "order-svc 归交易组，值班群改为 #trade-sre。", 4)],
         must=["#trade-sre"], must_not=["#trade-oncall"]),
    dict(id=16, cat="冲突", q="order-svc 现在能做 heap dump 吗？", at="2026-11-12 20:30", events=[
        w("2026-11-08", "procedural", "*", "晚高峰 19:00–22:00 不做 heap dump。", 9),
        ("revoke", "2026-11-11", "晚高峰 19:00–22:00 不做 heap dump"),
        w("2026-11-11", "procedural", "*", "P0 故障时任何时段都可以做 heap dump，其余时间避开晚高峰。", 9)],
         must=["P0 故障时"], must_not=["晚高峰 19:00–22:00 不做"]),
    dict(id=17, cat="冲突", q="order-svc 以前 Full GC 都是什么原因？", events=[
        w("2026-11-08", "episodic", "order-svc", "11-08 order-svc Full GC：按月导出一次读 41 万行进内存。", 8),
        w("2026-11-09", "episodic", "order-svc", "11-09 order-svc Full GC：OrderSnapshotCache 没设 maximumSize，占 2.1 GB。", 8)],
         must=["41 万行", "OrderSnapshotCache"]),
    dict(id=18, cat="冲突", q="order-svc 用的什么 GC？", events=[
        w("2026-11-02", "semantic", "order-svc/jvm", "order-svc：堆 4 GB，G1，6 个 Pod。", 5),
        w("2026-11-05", "semantic", "order-svc/jvm", "order-svc：堆 4 GB，G1，6 个 Pod。", 5)], must=["G1"], once=["G1，6 个 Pod"]),

    # ---------- 隐私 ----------
    dict(id=19, cat="隐私", q="order-svc 昨天有哪些下单失败？", events=[
        w("2026-11-08", "episodic", "order-svc", "11-08 order-svc 下单失败：日志片段 用户 13812345678 支付回调超时。", 5)],
         must=["支付回调超时"], must_not=["13812345678"]),
    dict(id=20, cat="隐私", q="order-svc 下单失败的用户是谁？", events=[
        w("2026-11-08", "episodic", "order-svc", "11-08 order-svc 下单失败：日志片段 用户 139-8765-4321 库存扣减超时。", 5)],
         must=["库存扣减超时"], must_not=["139-8765-4321"]),
]

# ---------- 第 21 个：⑤ 里发现“去掉新近项”20 个用例一个都没挂，补一个专门抓它的 ----------
CASE_21 = dict(id=21, cat="记住", q="order-svc 又 Full GC 了，是不是昨天那个问题？", events=[
    w("2026-09-15", "episodic", "order-svc", "09-15 order-svc Full GC：日志框架同步写入大对象 toString，老年代涨满。", 6),
    w("2026-08-02", "episodic", "order-svc", "08-02 大促 order-svc Full GC 频繁：堆 2 GB 不够，扩到 4 GB。", 6),
    w("2026-06-03", "episodic", "order-svc", "06-03 order-svc Full GC：本地缓存 HashMap 只增不删，老年代涨满。", 6),
    w("2026-11-08", "episodic", "order-svc", "11-08 order-svc 变慢：按月导出一次读 41 万行进内存。", 6)],
    must=["41 万行"])
