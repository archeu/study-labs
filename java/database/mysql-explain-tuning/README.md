# 明明有索引，它偏不走：执行计划与 SQL 调优

对应页面：[明明有索引，它偏不走：执行计划与 SQL 调优](https://archeu.github.io/study-notes/database/mysql-explain-tuning.html)

交易靶场的 `orders` 表（100 万行，和全景 J06、索引页同一份数据，多了 `order_no VARCHAR(20)` 和 `idx_created`），`PlanLab` 分六段：

- `setup`：建表、灌 100 万行、建 `uk_order_no` / `idx_user` / `idx_created`、`ANALYZE`
- `env`：版本、慢查询相关变量、统计信息、代价常量（`mysql.server_cost` / `engine_cost`）
- `slow`：开慢查询日志（`long_query_time=0.1`，同时写 `mysql.slow_log` 表），用 PreparedStatement 跑一段“业务流量”，再从慢日志和 `events_statements_summary_by_digest` 里找最该治的 SQL
- `fix`：三条慢 SQL（`setLong` 查 VARCHAR 订单号、`DATE(created_at)` 对账、`LIMIT 900000, 20`）改前改后的 EXPLAIN、警告、EXPLAIN ANALYZE、耗时
- `bench`：16 种 WHERE 写法，索引用没用上（页面实验台的数据）
- `cross`：命中比例 0.5%～50%，从 `optimizer_trace` 拿两种走法的代价，再分别强制走索引 / 全表扫计时；`user_id`（和主键无关）和 `created_at`（和主键同序）各一组

## 怎么跑

需要 Docker（会拉 `mysql:8.4`，约 800 MB）。按 JDK 8 写法（`--release 8`），用 JDK 17 跑过。容器的 buffer pool 开到 512 MB，数据全在内存里。

```bash
cd java/database/mysql-explain-tuning
./run.sh          # 起容器 → 灌 100 万行 → env slow fix bench cross
./run.sh again    # 容器留着，只重跑后面几段
./run.sh clean    # 删容器（连同数据卷）
```

当前用户不在 docker 组里时：`DOCKER="sudo docker" ./run.sh`。

## 预期输出

本机 2 核 ARM，JDK 17.0.21，Docker 里 MySQL 8.4.13（节选）：

```
[slow] performance_schema 按指纹汇总（总耗时前 5）：
     10 次  共   5.09 s  平均   509.20 ms  每次扫  1000000 行  无索引  10 次  SELECT * FROM `orders` WHERE `order_no` = ?
      3 次  共   2.52 s  平均   840.02 ms  每次扫  1900020 行  无索引   3 次  SELECT * FROM `orders` ORDER BY `created_at` DESC LIMIT ?, ...
      3 次  共   0.69 s  平均   231.30 ms  每次扫  1000000 行  无索引   3 次  SELECT COUNT ( * ) , SUM ( `amount_cents` ) FROM `orders` WHERE DATE ( `
[fix] ② 按订单号查：VARCHAR 列拿数字比
  改前       503.37 ms  type=ALL key=null rows=990792
         警告 1739: Cannot use ref access on index 'uk_order_no' due to type or collation conversion on field 'order_no'
  改后         0.77 ms  type=const key=uk_order_no rows=1
[fix] ③ 后台列表翻到第 45001 页
  改前 799.41 ms（ALL + filesort） 延迟关联 198.23 ms  游标分页 1.80 ms
[cross] user_id：和主键顺序无关，回表是随机跳页
   10.0%  估  206718 行  代价 走索引    72351.6 / 全表扫     101718  → 选 idx_user  实测 走索引 270.49 ms / 全表扫 206.56 ms
   15.0%  估  317564 行  代价 走索引     111148 / 全表扫     101718  → 选 全表扫    实测 走索引 398.15 ms / 全表扫 207.59 ms
[cross] created_at：和主键同序，回表是顺着往后读
   15.0%  估  317444 行  代价 走索引     111106 / 全表扫     101718  → 选 全表扫    实测 走索引 231.26 ms / 全表扫 252.79 ms
```

真实分界点：`user_id` 约 7.6%，`created_at` 约 16.4%；优化器对两者都在 10%～15% 之间换成全表扫。

## 练习

先猜结果，再运行。

1. 给对账查询设计覆盖索引，让 `Extra` 出现 `Using index`，耗时从 138 ms 降到多少？
2. 把 `slow` 段的 DAO 改成 `setString`、区间写法、游标分页，重跑 `slow`：指纹排行前两名应该换成按用户查、按 id 查。
3. `UPDATE orders SET status='CANCELLED' WHERE sku_id = 88 AND status = 'CREATED'` 先看 EXPLAIN；事务里执行不提交，另一个会话改 `sku_id = 89` 的一单，会被挡住吗？
4. 冷数据：每个比例跑前都重启容器（`--innodb-buffer-pool-load-at-startup=OFF`），再跑 `cross`。user_id 的分界点往哪边移？
