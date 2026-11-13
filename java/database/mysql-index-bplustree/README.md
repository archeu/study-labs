# 100 万行里找 9527，只读了 3 页：索引与 B+ 树

对应页面：[100 万行里找 9527，只读了 3 页：索引与 B+ 树](https://archeu.github.io/study-notes/database/mysql-index-bplustree.html)

交易靶场的 `orders` 表（100 万行，和全景 J06 同一份数据：订单 9527 属于用户 1024），`IndexLab` 分四段：

- `setup`：建表（主键 + `idx_user(user_id)`）、灌 100 万行、`ANALYZE`
- `shape <orders.ibd>`：直接读表空间文件，按页头的 `PAGE_INDEX_ID`、`PAGE_LEVEL`、`PAGE_N_RECS` 数每棵索引树每层几页、几条记录
- `reads`：7 条查询各拿了几次页（`Innodb_buffer_pool_read_requests` 的增量，预热后 5 次取最小）和 EXPLAIN
- `prefix`：加联合索引 `(user_id, created_at)`，把 `idx_user` 设成 INVISIBLE，看最左前缀哪些用得上；跑完还原

## 怎么跑

需要 Docker（会拉 `mysql:8.4`，约 800 MB）。按 JDK 8 写法（`--release 8`），用 JDK 17 跑过。

```bash
cd java/database/mysql-index-bplustree
./run.sh          # 起容器 → 灌 100 万行 → shape → reads → prefix
./run.sh again    # 容器留着，只重跑 shape / reads / prefix
./run.sh clean    # 删容器（连同数据卷）
```

当前用户不在 docker 组里时：`DOCKER="sudo docker" ./run.sh`。
`shape` 之前，`run.sh` 用 `FLUSH TABLES trade.orders FOR EXPORT` 把脏页刷回 `.ibd`，趁锁着把文件拷出来再 `UNLOCK TABLES`。

## 预期输出

本机 2 核 ARM，JDK 17.0.21，Docker 里 MySQL 8.4.13，两轮结果一致：

```
[shape] target/orders.ibd：7680 个页 × 16 KB
  idx_user（根页 5）
    第 2 层        ：     1 页         4 条记录
    第 1 层        ：     4 页      2048 条记录  平均每页   512 条
    第 0 层（叶子）：  2048 页   1000000 条记录  平均每页   488 条
  PRIMARY（根页 4）
    第 2 层        ：     1 页         6 条记录
    第 1 层        ：     6 页      4526 条记录  平均每页   754 条
    第 0 层（叶子）：  4526 页   1000000 条记录  平均每页   221 条
① 主键等值                       读页       3 次      0.74 ms   type=const key=PRIMARY rows=1
② 二级索引 + 回表                  读页      34 次      0.81 ms   type=ref key=idx_user rows=6
③ 覆盖索引                       读页      10 次      0.59 ms   type=ref key=idx_user rows=6 Extra=Using index
④ 没索引：全表扫                    读页    5430 次    345.09 ms   type=ALL key=null rows=995720
⑤ 10 万行：走 idx_user + 回表      读页  400228 次    258.03 ms   type=range key=idx_user rows=209916
⑥ 10 万行：不用索引，全表扫             读页    4812 次    185.47 ms   type=ALL key=null rows=995720
⑦ 同一范围但只要索引里有的列              读页     236 次     18.69 ms   type=range key=idx_user Extra=Using where; Using index
只有 idx_user：我的订单按时间倒序      type=ref   key=idx_user         Extra=Using filesort
我的订单按时间倒序                  type=ref   key=idx_user_created key_len=8    rows=6      Extra=Backward index scan
user_id 等值 + created_at 范围      type=range key=idx_user_created key_len=16   rows=6      Extra=Using index condition
只有 created_at（缺最左列）          type=ALL   key=null                          rows=995720 Extra=Using where
user_id 范围 + created_at 等值      type=range key=idx_user_created key_len=16   rows=381    Extra=Using index condition
```

拿页次数数的是 InnoDB 每一次“拿页”（同一页拿两次算两次），比按树高算的理论最少多，看量级。

## 练习

先猜结果，再运行。

1. 为 `SELECT id, status, amount_cents, created_at FROM orders WHERE user_id = ? ORDER BY created_at DESC LIMIT 20` 设计索引，做到 `Using index` 且没有 `Using filesort`。
2. 把第 ⑤ 条的拿页次数降两个数量级（覆盖索引，或改写查询）。
3. 建 `orders_uuid`，主键 `CHAR(36) DEFAULT (UUID())`，灌同样的数据跑 `shape`：叶子每页几行？`idx_user` 多大？
4. setup 改成 1000 万行：树会长到 4 层吗？先算再跑。
