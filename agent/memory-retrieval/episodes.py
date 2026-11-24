"""交易组的 25 条情景记忆（假数据，和全景一致）。另有 6 条风控组的，召回前就该被 team 过滤掉。

importance：写入时打的 1～10 分（这里手工给出；生产上由抽取时的模型打分，见 ⑥）。
"""

from dataclasses import dataclass


@dataclass(frozen=True)
class Episode:
    id: str
    team: str
    date: str
    importance: int
    text: str


EPISODES = [
    Episode("E1", "trade", "2026-11-08", 8, "11-08 20:16 order-svc Full GC：20:00 上线的按月导出一次读 41 万行进内存。处理：改分批导出，待发布。"),
    Episode("E2", "trade", "2026-10-21", 6, "10-21 payment-svc 接口超时：HikariCP 连接池打满，慢 SQL 占着连接不放。加索引后恢复。"),
    Episode("E3", "trade", "2026-10-30", 7, "10-30 order-svc 线程池打满：下游库存接口超时 3 秒，200 个 Tomcat 线程全在等。加了超时和熔断。"),
    Episode("E4", "trade", "2026-09-15", 6, "09-15 order-svc Full GC：日志框架同步写入大对象 toString，老年代涨满。改成异步日志。"),
    Episode("E5", "trade", "2026-11-05", 5, "11-05 cart-svc 响应变慢：Redis 大 key（单个购物车 8 MB），拆分后恢复。"),
    Episode("E6", "trade", "2026-07-20", 10, "07-20 order-svc 全量下单失败 40 分钟：发布时数据库迁移脚本锁表。P0，此后发布前必须评审 DDL。"),
    Episode("E7", "trade", "2026-11-08", 2, "11-08 09:10 cart-svc CPU 告警误报：监控采集周期配置错了，改回 15 秒。"),
    Episode("E8", "trade", "2026-11-07", 3, "11-07 coupon-svc 日志量突增：调试日志忘了关，关掉即可。"),
    Episode("E9", "trade", "2026-08-02", 7, "08-02 大促 order-svc Full GC 频繁：堆 2 GB 不够，扩到 4 GB，G1 参数调整。"),
    Episode("E10", "trade", "2026-10-11", 5, "10-11 order-svc P99 升到 1.2 秒：一次慢查询没走索引，加联合索引后恢复。"),
    Episode("E11", "trade", "2026-06-03", 8, "06-03 order-svc 内存泄漏：本地缓存 HashMap 只增不删，三天涨满老年代后频繁 Full GC。换成带上限的 Caffeine。"),
    Episode("E12", "trade", "2026-11-02", 4, "11-02 inventory-svc 偶发 502：网关超时设得比下游还短，调成 5 秒。"),
    Episode("E13", "trade", "2026-09-28", 5, "09-28 payment-svc 回调重复：幂等键没覆盖重试场景，补上唯一索引。"),
    Episode("E14", "trade", "2026-10-25", 3, "10-25 order-svc 单个 Pod 重启：节点被驱逐，其他 Pod 正常，无需处理。"),
    Episode("E15", "trade", "2026-11-06", 4, "11-06 order-svc 接口偶尔 800ms：下游 user-svc 冷启动，加了预热。"),
    Episode("E16", "trade", "2026-05-12", 9, "05-12 payment-svc 资金对账不平：消息重复消费导致重复入账，回滚并加幂等。"),
    Episode("E17", "trade", "2026-10-02", 6, "10-02 payment-svc 重启未摘流量，丢了 30 秒请求。此后重启前先摘流量。"),
    Episode("E18", "trade", "2026-09-05", 4, "09-05 cart-svc Redis 连接数打满：连接池参数被改小，恢复默认。"),
    Episode("E19", "trade", "2026-11-03", 5, "11-03 order-svc 导出接口超时：一次导出全年订单，限制导出范围为一个月。"),
    Episode("E20", "trade", "2026-08-19", 5, "08-19 coupon-svc 发券慢：Redis Lua 脚本里有循环，改成批量。"),
    Episode("E21", "trade", "2026-07-08", 6, "07-08 order-svc Young GC 停顿变长：新生代太小，调大后恢复。"),
    Episode("E22", "trade", "2026-10-17", 3, "10-17 inventory-svc 告警：磁盘 80%，清理旧日志。"),
    Episode("E23", "trade", "2026-11-04", 6, "11-04 payment-svc 偶发 Full GC：对账任务一次加载全天流水，改成分页。"),
    Episode("E24", "trade", "2026-09-22", 4, "09-22 order-svc 健康检查失败：探针超时太短，GC 停顿时被判死，调长超时。"),
    Episode("E25", "trade", "2026-10-07", 5, "10-07 cart-svc 购物车丢失：Redis 主从切换丢了 2 秒写入，改成同步写两份关键数据。"),
    # 风控组的：别的团队的经历，交易组的人问不到
    Episode("R1", "risk", "2026-11-08", 8, "11-08 risk-svc Full GC：规则引擎一次加载全部黑名单，改成布隆过滤器。"),
    Episode("R2", "risk", "2026-11-01", 6, "11-01 risk-svc 评分超时：特征服务慢，加缓存。"),
    Episode("R3", "risk", "2026-10-20", 5, "10-20 risk-svc 线程池打满：外部征信接口超时。"),
    Episode("R4", "risk", "2026-09-30", 7, "09-30 风控名单误杀：规则阈值配置错，回滚。"),
    Episode("R5", "risk", "2026-11-06", 4, "11-06 risk-svc 日志量突增：调试日志未关。"),
    Episode("R6", "risk", "2026-08-11", 6, "08-11 risk-svc 内存泄漏：模型对象未释放。"),
]

QUESTION = "10:42 告警：order-svc P99 1.6 秒。是不是昨天那个问题？"
TEAM = "trade"
TODAY = "2026-11-09"
