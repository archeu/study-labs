# 订单表拆成 8 张：分库分表与 ShardingSphere

对应页面：[订单表拆成 8 张：分库分表与 ShardingSphere](https://study.ainiya.de/database/sharding-shardingsphere.html)

## 怎么跑

```bash
DOCKER="sudo docker" ./run.sh new all   # 起 MySQL 8.4 容器（127.0.0.1:33111，库 ds_0 ds_1 bad_0 bad_1），跑 show 和 spread
./run.sh again show                      # 容器留着，只重跑 show
./run.sh clean                           # 删容器
```

- `ShardingLab show`：建表、插 24 个用户各 3 单，再执行 13 条逻辑 SQL，每条打印 ShardingSphere 的 `Logic SQL` / `Actual SQL`（`sql-show`，logger `org.apache.shardingsphere.sql`）。
- `ShardingLab spread`：1 万个用户各一单，比较 `sharding-good.yaml`（slot = user_id % 8，库 = slot % 2）和 `sharding-bad.yaml`（库 % 2、表 % 4）每张真实表的行数。
- 5.5.3 的 `shardingsphere-jdbc` 只带内核，pom.xml 里的分片、广播、MySQL 方言、Hikari 适配、单机模式仓库、权限实现缺一个就启动报错。

## 预期输出

2 核 ARM 虚拟机，JDK 17，ShardingSphere 5.5.3，MySQL 8.4.13：

```
==== 2. 按用户查：带分片键 → 1 条
Actual SQL: ds_0 ::: SELECT id, status FROM t_order_0 WHERE user_id = ? ORDER BY created_at DESC LIMIT 0, 20 ::: [1024]
==== 3. 只按订单号查：没有分片键 → 全路由 8 条
Actual SQL: ds_0 ::: SELECT … FROM t_order_0 WHERE id = ? UNION ALL … t_order_2 … t_order_4 … t_order_6 …
Actual SQL: ds_1 ::: SELECT … FROM t_order_1 WHERE id = ? UNION ALL … t_order_3 … t_order_5 … t_order_7 …
==== 7. 分片键上的范围：INLINE 算法默认不支持
   → 报错：… `allow-range-query-with-inline-sharding` is false, inline sharding algorithm can not tackle with range query.
==== 9. 改分片键：1024 → 1025（会换表）
   → 报错：SQLException: Can not update sharding value for table 't_order'.
==== 11. 跨片分页：LIMIT 4, 2 → 每张表 LIMIT 0, 6（8 条）
==== 规则 A：ds_0：t_order_0=1250 … 每张 1250
==== 规则 B：bad_0：t_order_0=2500  t_order_1=0  t_order_2=2500  t_order_3=0
```

## 练习

先猜结果，再运行。

1. 把 `sharding-bad.yaml` 改成“先算 slot”的写法，重跑 `spread`，确认 4 张表各 2500。
2. 给 `table_by_user` 加 `allow-range-query-with-inline-sharding: true`，重跑第 7 条，数一数它变成了几条真实 SQL。
3. 加一张 `t_order_item`（同样按 `user_id` 分），先不配 `bindingTables` 跑一次 JOIN，再配上跑一次，比较真实 SQL 的条数。
4. 把 `id` 改成 `AUTO_INCREMENT`、插入时不给 id，插完按 `id = 1` 查，看返回几行。
