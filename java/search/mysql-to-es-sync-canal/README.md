# 下单 1 秒后商家才搜得到：MySQL 到 ES 的同步（binlog、Canal、一致性）

对应页面：[下单 1 秒后商家才搜得到：MySQL 到 ES 的同步（binlog、Canal、一致性）](https://study.ainiya.de/search/mysql-to-es-sync-canal.html)

## 怎么跑

```bash
DOCKER="sudo docker" ./run.sh new all     # MySQL 8.4（33114）+ ES 9.5.5（19201）容器，本机 JDK 17 跑 Canal 1.1.8（11111），跑 e2e order outage purge
./run.sh clean                            # 停 Canal、删两个容器（data/ 里的 Canal 安装包留着）
```

- 第一次运行把 `canal.deployer-1.1.8.tar.gz` 下到 `data/`（gitignore），实例配置在 `canal-conf/orders/instance.properties`（只订阅 `order_db.t_order`）。
- `SyncLab`：canal.client 1.1.8 直连 Canal，`getWithoutAck` → bulk 写 ES → 成功才 `ack`；`shuffle` 模拟多分区乱序，`versioned` 用行里的 `version` 做 ES 外部版本号。
- `run.sh` 的 purge：停 Canal → 改 10 单 → `FLUSH BINARY LOGS; PURGE BINARY LOGS` → 再启 Canal，看 1236 错误和丢掉的变更。

## 预期输出

2 核 ARM 虚拟机，JDK 17，MySQL 8.4.13，Canal 1.1.8，ES 9.5.5：

```
e2e：提交 → Canal 中位 90 ms；bulk 中位 42 ms；refresh 中位 585 ms；合计中位 821 ms、最大 1599 ms
order：打乱顺序、不带版本 148 / 300 单停在旧状态；带外部版本号 0 单（409 拒掉 144 条）
outage：同步程序停 10 s，恢复后第一批 166 条积压，429 ms 追平
purge：errno = 1236 … Could not find first log file name in binary log index file；之后新订单也同步不过来
```

## 练习

1. 把 `ack` 挪到 bulk 之前，bulk 随机失败 10%，数 ES 少了几单。
2. order 里关掉 shuffle、也不带版本，看不一致是不是 0。
3. purge 之后写全量重灌脚本：删 meta.dat、记位点、全量导入新索引、回放、切别名。
4. `ALTER TABLE t_order ADD COLUMN remark VARCHAR(64)`，看同步程序和 strict mapping 的 ES 会怎样。
