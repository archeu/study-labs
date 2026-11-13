# 两次付款同时到，第二次改了 0 行：事务、MVCC 与锁（下一圈）

对应页面：[两次付款同时到，第二次改了 0 行：事务、MVCC 与锁（下一圈）](https://archeu.github.io/study-notes/database/mysql-lock-wait-deadlock.html)

交易靶场的 `orders` 表（100 万行，和全景 J06、索引页、执行计划页同一份数据），加一张扣款流水 `payments`。`LockLab` 分五段：

- `setup`：建表、灌 100 万行、建 `uk_order_no` / `idx_user` / `idx_created`，建 `payments`
- `pay`：用户 1024 连点两次付款，A、B 两个事务按固定顺序执行，A 拿着锁 200 ms。先查后改 / 条件更新 / `FOR UPDATE` 三种写法各扣了几次款
- `locks`：主键、`idx_user`、没索引的 `sku_id` 三种 WHERE，在 RR 和 RC 下各锁了多少（`innodb_trx` + `performance_schema.data_locks`），另一个会话改别的单、插新单、普通 SELECT 会不会被挡
- `wait`：锁等待超时 1205 只回滚那一条语句，事务还开着；等的时候 `sys.innodb_lock_waits` 长什么样
- `deadlock`：两笔批量操作按相反顺序改 9527 和 9530，谁被回滚，`LATEST DETECTED DEADLOCK` 怎么读

## 怎么跑

需要 Docker（会拉 `mysql:8.4`，约 800 MB）。按 JDK 8 写法（`--release 8`），用 JDK 17 跑过。实验会话的 `innodb_lock_wait_timeout` 设成 1 秒，免得被挡时干等 50 秒。

```bash
cd java/database/mysql-lock-wait-deadlock
./run.sh          # 起容器 → 灌 100 万行 → pay locks wait deadlock
./run.sh again    # 容器留着，只重跑四段
./run.sh clean    # 删容器（连同数据卷）
```

当前用户不在 docker 组里时：`DOCKER="sudo docker" ./run.sh`。

## 预期输出

本机 2 核 ARM，JDK 17.0.21，Connector/J 9.7.0，Docker 里 MySQL 8.4.13（节选；逐条锁和死锁日志全文见运行输出）：

```
[pay] 用户 1024 连点两次付款，两个请求 A、B 几乎同时到；A 拿着锁 200 ms 后提交。RR（默认）。
  写法 1 先查后改   A 读到 CREATED 改了 1 行 | B 读到 CREATED，UPDATE 等了 206 ms，改了 1 行 → 扣款 2 次
  写法 2 条件更新   A 改了 1 行 | B 先读到 CREATED，UPDATE 等了 204 ms，改了 0 行 → 扣款 1 次
                    B 事务里接着读：普通 SELECT 读到 CREATED，SELECT … FOR SHARE 读到 PAID
  写法 3 FOR UPDATE A 读到 CREATED | B 的 FOR UPDATE 等了 205 ms，读到 PAID → 扣款 1 次
[locks RR] 主键       UPDATE orders SET status='PAID' WHERE id=9527 AND status='CREATED'
    改了 1 行，用时 3 ms，trx_rows_locked=1 lock_structs=2 锁内存=1.1 KB
    另一个会话：改 9528（隔壁一单） 通过 1ms；改 10（无关的一单） 通过 1ms；插一单 user 1024 通过 2ms；插一单 user 1023 通过 1ms；普通 SELECT 9527 通过 1ms；
[locks RR] idx_user UPDATE orders SET status='PAID' WHERE user_id=1024 AND status='CREATED'
    改了 1 行，用时 1 ms，trx_rows_locked=13 lock_structs=9 锁内存=1.1 KB
    另一个会话：改 9528（隔壁一单） 通过 1ms；改 10（无关的一单） 通过 1ms；插一单 user 1024 被挡 1205（1031ms）；插一单 user 1023 被挡 1205（1001ms）；普通 SELECT 9527 通过 1ms；
[locks RR] 没索引      UPDATE orders SET status='PAID' WHERE sku_id=88 AND status='CREATED'
    改了 1 行，用时 717 ms，trx_rows_locked=1009174 lock_structs=9175 锁内存=1048.1 KB
    另一个会话：改 9528（隔壁一单） 被挡 1205（1001ms）；改 10（无关的一单） 被挡 1205（1001ms）；插一单 user 1024 被挡 1205（1001ms）；插一单 user 1023 被挡 1205（1001ms）；普通 SELECT 9527 通过 1ms；
[locks RC] 主键       UPDATE orders SET status='PAID' WHERE id=9527 AND status='CREATED'
    改了 1 行，用时 1 ms，trx_rows_locked=1 lock_structs=2 锁内存=1.1 KB
    另一个会话：改 9528（隔壁一单） 通过 1ms；改 10（无关的一单） 通过 1ms；插一单 user 1024 通过 2ms；插一单 user 1023 通过 1ms；普通 SELECT 9527 通过 1ms；
[locks RC] 没索引      UPDATE orders SET status='PAID' WHERE sku_id=88 AND status='CREATED'
    改了 1 行，用时 1164 ms，trx_rows_locked=1 lock_structs=9175 锁内存=1048.1 KB
    另一个会话：改 9528（隔壁一单） 通过 1ms；改 10（无关的一单） 通过 1ms；插一单 user 1024 通过 1ms；插一单 user 1023 通过 1ms；普通 SELECT 9527 通过 1ms；
[wait] innodb_rollback_on_timeout = 0，全局 innodb_lock_wait_timeout = 50 秒（本实验的会话设成 1 秒）
  B 第二条语句等了 1037 ms：MySQLTransactionRollbackException 1205 SQLState=40001：Lock wait timeout exceeded; try restarting transaction
  报错之后 B 的事务：还开着 state=RUNNING rows_modified=1 rows_locked=2
  B 直接 COMMIT 后：9530 = CANCELLED，9527 = PAID  ← 批量取消只做了一半
[deadlock] innodb_deadlock_detect = 1，A 合并付款按 9527 → 9530，B 批量取消按 9530 → 9527；等锁上限改回 50 秒
  A 的第二条（先发，等了 230 ms）：成功，改了 1 行
  B 的第二条（后发，28.3 ms 返回）：MySQLTransactionRollbackException 1213 SQLState=40001：Deadlock found when trying to get lock; try restarting transaction
  报错之后 A：还开着 state=RUNNING rows_modified=2 rows_locked=2
  报错之后 B：已经没有了（整个回滚）
  都提交后：9527 = PAID，9530 = PAID
    *** WE ROLL BACK TRANSACTION (2)
```

RC 下 `idx_user` 那组：改 1 行，`data_locks` 只剩 `idx_user X,REC_NOT_GAP 1024, 9527` 和 `PRIMARY X,REC_NOT_GAP 9527` 两把，另一个会话全部通过。

`information_schema.innodb_trx` 读的是最多每 0.1 秒刷新一次的缓存，代码里在查它之前等了 150 ms。

## 练习

先猜结果，再运行。

1. 把写法 1 的 UPDATE 加上 `AND status='CREATED'`，扣款改成看影响行数；再给 `payments` 加 `UNIQUE(order_id)`，故意删掉条件跑一次。预期：第一次扣款 1 次；第二次 B 插流水时报 1062。
2. `ALTER TABLE orders ADD KEY idx_sku (sku_id)`，重跑 `locks`：RR 下“没索引”那组还剩几把锁？哪些操作被挡？
3. 在 `deadlock()` 里捕获 1213（SQLState 40001），rollback 后把 B 的整个事务重跑一次，最多 3 次，每次随机等 10～50 ms。预期：9527、9530 都是 PAID，B 重跑后两条都改 0 行。
