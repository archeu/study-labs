"""值班助手 v2 的记忆表：交易组的 12 条，三种类型混在一起（假数据，和全景“第二天再问同一个服务”一致）。

kind:  episodic   情景：发生过的一件事，带时间、经过、结果       —— 追加，不改
       semantic   语义：关于某个对象的事实，同一个 key 只有一个值 —— 覆盖（upsert）
       procedural 程序性：怎么做、什么不能做                      —— 人确认后生效，整组加载
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class Memory:
    id: str
    kind: str          # episodic / semantic / procedural
    team: str          # 按团队隔离
    key: str           # semantic: 对象和属性；episodic: 服务名；procedural: 适用范围
    text: str
    date: str


MEMORIES = [
    Memory("E1", "episodic", "trade", "order-svc", "11-08 20:16 order-svc Full GC：20:00 上线的“按月导出订单”一次读 41 万行进内存。处理人：值班的你。修复：改分批导出，待发布。", "2026-11-08"),
    Memory("E2", "episodic", "trade", "payment-svc", "10-21 payment-svc 接口超时：HikariCP 连接池打满，慢 SQL 占着连接不放。加索引后恢复。", "2026-10-21"),
    Memory("E3", "episodic", "trade", "order-svc", "10-30 order-svc 线程池打满：下游库存接口超时 3 秒，200 个 Tomcat 线程全在等。加了超时和熔断。", "2026-10-30"),
    Memory("E4", "episodic", "trade", "order-svc", "09-15 order-svc Full GC：日志框架同步写入大对象 toString，老年代涨满。改成异步日志。", "2026-09-15"),
    Memory("E5", "episodic", "trade", "cart-svc", "11-05 cart-svc 响应变慢：Redis 大 key（单个购物车 8 MB），拆分后恢复。", "2026-11-05"),
    Memory("P1", "procedural", "trade", "*", "值班的你说过：晚高峰 19:00–22:00 不做 heap dump。", "2026-11-08"),
    Memory("P2", "procedural", "trade", "payment-svc", "重启 payment-svc 之前必须先摘流量，并在 #trade-oncall 里说一声。", "2026-10-02"),
    Memory("P3", "procedural", "trade", "*", "排查变慢先看最近一次发布记录，再看 GC 和线程。", "2026-09-20"),
    Memory("S1", "semantic", "trade", "order-svc/jvm", "order-svc：堆 4 GB，G1，6 个 Pod。", "2026-11-02"),
    Memory("S2", "semantic", "trade", "payment-svc/db", "payment-svc：HikariCP maximumPoolSize=10，MySQL 8.0 主从。", "2026-10-21"),
    Memory("S3", "semantic", "trade", "order-svc/owner", "order-svc 归交易组，值班群 #trade-oncall。", "2026-09-01"),
    Memory("S4", "semantic", "trade", "cart-svc/redis", "cart-svc：Redis Cluster 3 主 3 从。", "2026-08-30"),
]

QUESTION = "10:42 告警：order-svc P99 1.6 秒。是不是昨天那个问题？"
SERVICE = "order-svc"
