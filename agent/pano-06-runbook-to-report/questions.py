"""站 06 的评测集：30 个有答案的问题 + 3 个知识库里没有答案的问题。

gold 是“答案在哪”：(文档 key, 章节标记)。一片算命中：文档 key 相同，且章节路径里有“› 标记”。
用章节而不用片 id，是为了换了切块策略以后评测集还能用。
kind：literal 问题里有文档原词；para 换了说法；table 答案在表格里；ocr 答案只在扫描页上；ticket 在故障单里；note 在学习笔记里。
"""
RB, T08, T09, CAF = "order-svc-runbook", "INC-20261108-01", "INC-20261109-02", "caffeine-local-cache"

QUESTIONS = [
    ("Q01", "order-04 接口的 P99 超过多少会告警？", [(RB, "2 告警")], "table", "800 ms，持续 3 分钟，P2"),
    ("Q02", "Old 区占用到多少、持续多久会触发告警？", [(RB, "2 告警")], "table", "85%，持续 5 分钟，P2"),
    ("Q03", "什么情况下会直接打电话叫值班的人？", [(RB, "2 告警")], "para", "P1 告警：错误率 > 1% 持续 2 分钟，或 10 分钟内 Full GC > 3 次"),
    ("Q04", "堆被慢慢吃满、Full GC 越来越多，第一步该看什么？", [(RB, "3.1 ")], "para", "看 GC 日志，确认 Old 区回收后是否还在涨"),
    ("Q05", "晚上八点能不能做 heap dump？", [(RB, "3.1 ")], "para", "不能，晚高峰 19:00–22:00 不做；必须做先摘流量"),
    ("Q06", "为什么不建议直接 dump 堆？", [(RB, "3.1 ")], "para", "4 GB 堆的 dump 会让 Pod 停顿 8 秒以上"),
    ("Q07", "接口变慢了，排查的先后顺序是什么？", [(RB, "3.2 ")], "para", "最近发布 → GC → 慢 SQL → 下游"),
    ("Q08", "连接池等待线程告警，能不能直接把连接池调大？", [(RB, "3.3 ")], "literal", "不能，6 × 20 = 120 个连接，从库 max_connections 300"),
    ("Q09", "order-svc 的数据库连接池最多几个连接？", [(RB, "3.3 ")], "para", "maximumPoolSize=20"),
    ("Q10", "一直处理失败的消息最后去了哪里？", [(RB, "3.4 ")], "para", "失败超过 3 次进死信 topic order-events-dlq"),
    ("Q11", "多开几个 Pod 能不能加快 order-events 的消费？", [(RB, "3.4 ")], "literal", "不能，12 个分区最多 12 个消费者"),
    ("Q12", "新版本上线先发几个 Pod、观察多久？", [(RB, "4.1 ")], "para", "先发 1 个 Pod，观察 15 分钟"),
    ("Q13", "发布后多长时间内出问题可以不查原因直接回滚？", [(RB, "4.2 ")], "para", "30 分钟内，P99 翻倍或错误率 > 1%"),
    ("Q14", "回滚 order-svc 的命令是什么？", [(RB, "4.2 ")], "literal", "kubectl rollout undo deploy/order-svc -n trade"),
    ("Q15", "本地缓存上线前必须配置哪两项？", [(RB, "4.3 ")], "literal", "maximumSize（或 maximumWeight）和 expireAfterWrite"),
    ("Q16", "4 GB 的堆，本地缓存最多能占多大？", [(RB, "4.3 ")], "para", "堆的 10%，即 400 MB"),
    ("Q17", "本地缓存命中率很低该怎么处理？", [(RB, "4.3 ")], "para", "低于 50% 说明没起作用，应该删掉"),
    ("Q18", "要导出 20 万行订单，应该怎么做？", [(RB, "4.4 ")], "para", "超过 10 万行走异步任务，夜间 01:00–05:00，每批 ≤ 5000 行查从库"),
    ("Q19", "G1 的停顿目标设的是多少？", [(RB, "5.1 ")], "table", "-XX:MaxGCPauseMillis=200"),
    ("Q20", "OOM 时自动生成的堆转储放在哪个目录？", [(RB, "5.1 ")], "table", "/data/dump（挂了持久卷）"),
    ("Q21", "GC 日志写在哪，保留几天？", [(RB, "5.2 ")], "literal", "/data/logs/order-svc/gc.log，保留 7 天"),
    ("Q22", "想临时停用本地缓存，改哪个开关？", [(RB, "附录")], "ocr", "order.cache.local.enabled"),
    ("Q23", "降级开关改完多久生效，要不要重启？", [(RB, "附录")], "ocr", "1 分钟内生效，不用重启"),
    ("Q24", "找 DBA 应急去哪个群？", [(RB, "附录")], "ocr", "#dba-oncall"),
    ("Q25", "order-svc 部署了几个 Pod，每个多大？", [(RB, "1.1 ")], "literal", "6 个 Pod，每个 4 核 8 GB"),
    ("Q26", "订单详情是哪个接口编号，前面有什么缓存？", [(RB, "1.2 ")], "para", "order-04，Caffeine 本地缓存"),
    ("Q27", "11 月 8 日那次 Full GC 是什么原因？", [(T08, "根因")], "ticket", "按月导出一次读 41 万行进内存"),
    ("Q28", "11 月 9 日 P99 升高时是怎么止血的？", [(T09, "时间线")], "ticket", "11:05 关闭 order.cache.local.enabled，改查 Redis"),
    ("Q29", "OrderDetailCache 打算怎么修？", [(T09, "修复")], "ticket", "补 maximumSize(50000) 和 expireAfterWrite(10 分钟)，随 v2.3.2 发布"),
    ("Q30", "Caffeine 超过容量上限时按什么策略淘汰？", [(CAF, "容量上限")], "note", "W-TinyLFU"),
]

NO_ANSWER = [
    ("N01", "order-svc 的 Redis 集群密码是多少？"),
    ("N02", "pay-svc 的 P99 告警阈值是多少？"),
    ("N03", "inventory-svc 怎么回滚？"),
]


def is_hit(row, gold):
    """row 是检索结果（有 doc_key、section），gold 是 [(key, 标记)]。"""
    return any(row["doc_key"] == k and f"› {m}" in row["section"] + " " for k, m in gold)


def first_hit_rank(rows, gold):
    """正确的片排第几（从 1 数）；没出现返回 None。"""
    for i, r in enumerate(rows, start=1):
        if is_hit(r, gold):
            return i
    return None
