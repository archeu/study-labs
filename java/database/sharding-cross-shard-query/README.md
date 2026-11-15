# 9527 在哪张表：分片以后的跨片查询、分页与全局表

对应页面：[9527 在哪张表：分片以后的跨片查询、分页与全局表](https://archeu.github.io/study-notes/database/sharding-cross-shard-query.html)

## 怎么跑

```bash
DOCKER="sudo docker" ./run.sh new all     # 起 MySQL 8.4 容器（127.0.0.1:33112，库 ds_0 ds_1），灌 80 万行，跑全部实验
./run.sh again page                       # 数据留着，只重跑某一段：load gene page agg join unique
./run.sh clean                            # 删容器
```

- `CrossShardLab load`：8 张 `t_order_N` 各 10 万行（前 5 万老订单号、后 5 万带基因的新订单号，9527 属于用户 1024）+ 8 张 `t_order_item_N`。
- `GeneShardingAlgorithm`：`ComplexKeysShardingAlgorithm`，有 user_id 用 user_id % 8；只有新订单号用 id % 8；只有老订单号广播。
- `gene.yaml` / `gene-quiet.yaml`：基因复合分片（后者关掉 sql-show 用来计时）；`join-no.yaml` / `join-yes.yaml`：不配 / 配绑定表。

## 预期输出

2 核 ARM 虚拟机，JDK 17，ShardingSphere 5.5.3，MySQL 8.4.13：

```
==== gene 1. 只拿新订单号 8000480000 → ds_0 ::: … FROM t_order_0 WHERE id = ?
==== gene 2. 只拿老订单号 9527 → 每个库一条 UNION ALL，问遍 8 张表
==== gene 4. t_order_7 里 id = 9527 的行数：0（它其实在 t_order_0）
==== page：ORDER BY created_at DESC LIMIT offset, 20
   offset   每张表取   耗时(ms)   JVM 分配(MB)
        0         20       17.6            0.8
     1000       1020      292.9           30.0
    10000      10020      340.0          275.3
   100000     100020     2411.2         2743.3
   游标：23.0 ms，0.7 MB；单表直连 LIMIT 100000, 20：61 ms
==== agg 1. AVG → AVG, COUNT(...) AS AVG_DERIVED_COUNT_0, SUM(...) AS AVG_DERIVED_SUM_0
==== agg 2. GROUP BY … LIMIT 3 → 每张表 LIMIT 2147483647
==== join：没配绑定表 8 条 / 32 条；配了 2 条 / 8 条
==== unique：两个用户各插 order_no = 'DUP-1'，都成功，COUNT = 2
```

耗时随机器波动，看量级和比例。offset 1000 起变慢，是 MySQL 对 `LIMIT 0, 1020` 改走全表扫描 + filesort（`EXPLAIN` 可见）。

## 练习

1. 给老单号建映射表 `t_order_index(order_id, user_id)`，让 9527 先查映射、再查 1 张表。
2. 把 `max-connections-size-per-query` 设成 4，重跑 page；再给 JDBC URL 加 `useCursorFetch=true&defaultFetchSize=1000`。
3. 分页 SQL 加 `FORCE INDEX(idx_created)`，比较 offset 1000 的耗时。
4. 建 `t_order_no(order_no PRIMARY KEY)` 按 order_no 分片，插订单前先占位，重跑 unique。
