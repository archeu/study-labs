# commit 返回了，数据页还是旧的：redo、undo、binlog 与两阶段提交

对应页面：[commit 返回了，数据页还是旧的：redo、undo、binlog 与两阶段提交](https://archeu.github.io/study-notes/database/mysql-redo-undo-binlog.html)

交易靶场的 `orders` 表（100 万行，和全景 J06 同一份数据）。看一条付款 UPDATE 在 redo、undo、binlog 三本账上各留下什么，再在提交途中 kill -9 mysqld，看重启后怎么对账。

- `LogLab setup`：建表、灌 100 万行
- `LogLab see`：付款 UPDATE 后看 redo LSN、binlog 位置、未提交事务、另一个连接读到什么；ROLLBACK 一次、COMMIT 一次，列出 binlog 事件
- `LogLab purge`：一个 RR 报表事务开着，9527 被改 2 万次：History list length、旧快照沿版本链读的耗时、提交后 purge 多快清完
- `LogLab redo`：redo 容量 8 MB 和 1 GB，跑同样的 30 万行批量更新（每批 5000 行）：用时、最慢一批、检查点最多落后多少、刷了多少页
- `crash.sh`：30 万行的大事务，三次 kill -9：UPDATE 后未提交 / binlog 写到 1/3 / binlog 刚写完整；打印重启时的恢复日志（会把 `log_error_verbosity` 设成 3）和 9527 的结果

## 怎么跑

需要 Docker（会拉 `mysql:8.4`，约 800 MB）。按 JDK 8 写法（`--release 8`），用 JDK 17 跑过。`crash.sh` 要在宿主机上 `sudo kill -9` 容器里的 mysqld（它在容器里是 1 号进程，容器内部杀不掉），并通过 `/proc/<pid>/root` 盯着 binlog 文件长度。

```bash
cd java/database/mysql-redo-undo-binlog
DOCKER="sudo docker" ./run.sh         # 起容器 → 灌 100 万行 → see purge redo → crash
DOCKER="sudo docker" ./run.sh again   # 容器留着，重跑 see purge redo crash
DOCKER="sudo docker" ./run.sh crash   # 只重跑三次 kill
DOCKER="sudo docker" ./run.sh clean   # 删容器（连同数据卷）
```

## 预期输出

本机 2 核 ARM，JDK 17.0.21，Connector/J 9.7.0，Docker 里 MySQL 8.4.13（buffer pool 512 MB，其余默认）。节选：

```
[see] 付款 UPDATE，最后 ROLLBACK
  ROLLBACK 用时 4.01 ms | redo LSN 再 +296 字节 | binlog ... （+0 字节）| 现在 9527 = CREATED
[see] 付款 UPDATE，最后 COMMIT
  UPDATE 改了 1 行 | redo LSN +232 字节 | binlog 位置不动 | 另一个连接读到 CREATED
  COMMIT 用时 6.29 ms | redo LSN 再 +317 字节 | binlog +465 字节 | 现在 9527 = PAID
    Anonymous_Gtid 79 / Query(BEGIN) 97 / Table_map 73 / Update_rows 185 / Xid 31
[purge] 报表事务开着，9527 被改了 20000 次；3 秒后 History list length 13 → 20000
  报表事务读 9527：qty=2，14.21 ms（沿 undo 版本链往回走 20000 步） | 新连接读：qty=20002，0.88 ms
  报表事务提交后 0.6 s，History list length 降到 0
[redo] 容量    8 MB：改 300000 行用时 3.2 s，最慢一批 118 ms | 写了 redo 29.1 MB | 检查点最多落后 5.9 MB | 期间刷了 3747 个页（59 MB）
[redo] 容量 1024 MB：改 300000 行用时 2.8 s，最慢一批 74 ms | 写了 redo 29.1 MB | 检查点最多落后 28.8 MB | 期间刷了 709 个页（11 MB）
[crash 0] UPDATE 做完、没 COMMIT 就 kill -9
      [Note] [MY-013011] [InnoDB] Rolling back trx with id 43786, 300000 rows to undo
  9527 的 qty：kill 前 9 → 重启后 9
[crash A] COMMIT 途中，binlog 写到三分之一 就 kill -9
      [Note] [MY-010224] [Server] Found 1 prepared transaction(s) in InnoDB
      [Note] [MY-013911] [Server] Crash recovery finished in InnoDB engine. Successfully rolled back 1 internal transaction(s).
      [Note] [MY-010858] [Server] Crashed binlog file ./binlog.000006 size is 16023552, but recovered up to 158. Binlog trimmed to 158 bytes.
  9527 的 qty：kill 前 9 → 重启后 9
[crash B] COMMIT 途中，binlog 刚写完整 就 kill -9
      [Note] [MY-010224] [Server] Found 1 prepared transaction(s) in InnoDB
      [Note] [MY-013911] [Server] Crash recovery finished in InnoDB engine. Successfully committed 1 internal transaction(s).
  9527 的 qty：kill 前 9 → 重启后 10
```

LSN 是全库共用的：`see` 第一轮 UPDATE 量到的 redo 里常混着后台 purge 的写入（实测 932～964 字节），看第二轮更准。

## 练习

先猜结果，再运行。

1. 把 `purge()` 里报表事务的隔离级别改成 `READ COMMITTED`，重跑 `purge`：History list length 还会涨到 2 万吗？
2. 把 `crash.sh` 的大事务改成每 5000 行一提交，在第 30 批时 kill：重启后 9527 的 qty 变没变？日志里还有 “300000 rows to undo” 吗？
3. 加一个 `gc` 段：1、8、32 个线程各做 2000 次自动提交的单行 UPDATE，前后读 `Innodb_os_log_fsyncs`：每次提交平均几次 redo fsync？
