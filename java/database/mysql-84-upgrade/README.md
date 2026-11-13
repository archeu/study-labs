# 换上 8.4，付款服务连不上库了：MySQL 8.0 升级 8.4 LTS

对应页面：[换上 8.4，付款服务连不上库了：MySQL 8.0 升级 8.4 LTS](https://archeu.github.io/study-notes/database/mysql-84-upgrade.html)

交易靶场的一主一从（GTID），从 MySQL 8.0.46 按“先升从库 → 切换 → 最后升旧主库”滚动升级到 8.4.13。故意带上老库常见的三样东西：`mysql_native_password` 建的付款账号、`CHANGE MASTER TO` 搭的复制、一个指向非唯一索引的外键。

- `run.sh`：升级剧本，六段：
  1. 两台 8.0.46，老运维脚本搭复制，付款服务连接矩阵（Connector/J 9.7.0 和 5.1.49）
  2. 8.4 镜像里的 MySQL Shell 跑 `util check-for-server-upgrade`
  3. 从库慢关机，换 8.4.13 镜像原地升级：日志、复制、老命令、外键
  4. 切换；连接矩阵；修法 A（改账号插件）、`caching_sha2_password` 的缓存、修法 B（`--mysql-native-password=ON`）
  5. 旧主库升 8.4 挂回来当从库（会撞上 1524，再修）；8.0 / 8.4 变量对比
  6. 用 8.0.46 启动已经升过级的数据卷（单向门）
- `ConnectMatrix.java`：两个账号 × 三种 JDBC URL，各连一次并付一笔款；`cache` 模式演示缓存冷热。

## 怎么跑

需要 Docker、JDK 8+、Maven。端口用 127.0.0.1:33094 / 33095。

```bash
cd java/database/mysql-84-upgrade
DOCKER="sudo docker" ./run.sh          # 约 3 分钟；变量对比和检查报告写在 target/out/
DOCKER="sudo docker" ./run.sh clean    # 删容器、数据卷和网络
```

## 预期输出

2026-11-13，2 核 ARM / 11 GB，Docker，JDK 17，`orders` 10 万行（节选）：

```
### 3. 从库原地升级：慢关机 → 换 mysql:8.4.13 镜像，同一个数据卷
  慢关机用了 1.8 s
  up-b 可连接，用了 10.3 s
    [System] Data dictionary upgrading from version '80023' to '80300'.
    [Warning] [MY-015129] Foreign key 'refunds_ibfk_1' ... refers to non-unique key or partial key. ...
    [System] Server upgrade from '80046' to '80413' started.
    [System] Server upgrade from '80046' to '80413' completed.
  新语法 SHOW REPLICA STATUS: Source_Host: up-a Replica_IO_Running: Yes Replica_SQL_Running: Yes
  老命令 SHOW SLAVE STATUS → ERROR 1064 (42000) ...
  在 8.4 上再建一个指向非唯一索引的外键 → ERROR 6125 (HY000) ... Missing unique key ...

== 新主库 8.4.13 · 驱动 com.mysql.cj.jdbc.Driver 9.7
  pay_native | 老写法 useSSL=false   | FAIL  SQLException [HY000/1524] Plugin 'mysql_native_password' is not loaded
  pay_sha2   | 老写法 useSSL=false   | FAIL  SQLNonTransientConnectionException [08001/0] Public Key Retrieval is not allowed
  pay_sha2   | 默认（走 TLS）         | OK    付款改了 1 行（server 8.4.13）

### 6. 单向门：用 mysql:8.0.46 启动已经升过级的数据卷
  [ERROR] [MY-014061] [InnoDB] Invalid MySQL server downgrade: Cannot downgrade from 80413 to 80046. ...
```

## 练习

先猜结果，再运行。

1. 把“救火”换成“提前修”：在第 1 段末尾对 8.0 主库 `ALTER USER pay_native ... caching_sha2_password`，删掉修法 B。第 5 段的从库还会停吗？
2. 切换时有付款在跑：后台每 20 ms 付一笔款，记录切换期间失败的笔数和报错；再加“切换后重建连接”。
3. 带一份老 my.cnf（`expire_logs_days=7`、`default_authentication_plugin=mysql_native_password`）升级，检查时加 `--config-path`。检查报不报？8.4 起不起得来？
4. 回退演练：切换后另起一个 8.0.46，以 8.4 新主库为源复制（不受支持），看它能跑多远。
