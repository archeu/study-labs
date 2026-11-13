# 全景：一条 UPDATE 从应用到磁盘

对应页面：[全景：一条 UPDATE 从应用到磁盘](https://archeu.github.io/study-notes/java/pano-j06-update-to-disk.html)

订单 9527 付款：`UPDATE orders SET status='PAID', paid_at=NOW(6) WHERE id=9527 AND status='CREATED'`，然后 `COMMIT`。
`UpdateToDisk` 用 HikariCP + Connector/J 发这条 UPDATE，另开一条连接在旁边看它走过的每一站：
执行计划、读盘、行锁、undo（MVCC 读）、redo LSN、binlog 事件、提交耗时、从库回放、脏页。

## 怎么跑

需要 Docker（会拉 `mysql:8.4`，约 800 MB）。

```bash
cd java/java/pano-j06-update-to-disk
./run.sh            # 起一主一从 → 灌 100 万行 → 重启主库（buffer pool 变冷）→ 追踪 → 提交开销对比
./run.sh again      # 只重启主库、再追踪一次
./run.sh clean      # 删容器和网络
```

当前用户不在 docker 组里时：`DOCKER="sudo docker" ./run.sh`。

- `run.sh` 里主库关掉了 `innodb_buffer_pool_load_at_startup` / `dump_at_shutdown`（8.4 默认都开），否则重启会把热页自动读回来，看不到冷读。
- 从库 `SOURCE_CONNECT_RETRY=2`。默认 60 秒：主库一重启，从库要等一分钟才重连。
- `UpdateToDisk` 有三个入口：`setup`（建表灌数）、`trace`（追踪一条 UPDATE）、`bench`（`innodb_flush_log_at_trx_commit` / `sync_binlog` 两组配置各 300 次自动提交）。

## 预期输出

本机 2 核 ARM，JDK 21.0.13，两个 MySQL 8.4.13 容器在同一台机器上，3 次追踪的范围：

```
== 第 4 站：优化器怎么找这一行（EXPLAIN）==
  ... type=range | key=PRIMARY | rows=1          (sku_id=88 没索引：type=index rows=995720)
== 第 3～8 站：executeUpdate 返回 1 行，客户端计时 3.1～4.5 ms（buffer pool 是冷的）==
读盘：Innodb_buffer_pool_reads +3            根页 4 → 内节点页 134 → 叶子页 75（脏）
== 第 6 站 ==  TABLE IX + RECORD PRIMARY X,REC_NOT_GAP 9527；另一个事务改 9527 → 1205 锁等待超时
== 第 7 站 ==  另一个连接此刻读 9527：status=CREATED
== 第 8 站 ==  redo LSN +228～244 字节；binlog 位点不动
== 第 9 站：commit() 3.4～3.7 ms ==  waiting for handler commit 2.9～3.3 ms；binlog +423 字节（Gtid/BEGIN/Table_map/Update_rows/Xid）
应用这边一共 7.4～9.3 ms
从库上读到 9527=PAID：18～24 ms；applier 记录 7.7～9.4 ms
9527 所在的脏页：提交后 120 s 仍是脏页
[bench] 双 1：p50 4.19 ms；2/0：p50 1.20 ms
```

`Innodb_redo_log_current_lsn` 状态变量在实测里不跟着变，LSN 取自 `SHOW ENGINE INNODB STATUS` 的 LOG 段。

## 练习

先猜结果，再运行。

1. 把 `setup` 里的 100 万行改成 1000 万：冷启动的第一单要读几个页？
2. 把 `UPDATE_SQL` 的条件换成 `sku_id = ?`，另一个事务改一条毫不相干的订单，会不会也等锁？
3. 打开 `run.sh` 里被关掉的 buffer pool 预热，再 `./run.sh again`：`Innodb_buffer_pool_reads` 还是 +3 吗？
4. 第 12 站：同时开一个持续写入的压测，提交后多久页 75 才被刷回去？
