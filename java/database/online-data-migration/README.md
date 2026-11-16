# 1.2 亿行搬进 8 张表，App 不停：不停机数据迁移

对应页面：[1.2 亿行搬进 8 张表，App 不停：不停机数据迁移](https://study.ainiya.de/database/online-data-migration.html)

## 怎么跑

```bash
DOCKER="sudo docker" ./run.sh new all     # 起 MySQL 8.4 容器（127.0.0.1:33113，旧库 order_db，新库 ds_0 ds_1），naive 和 safe 各跑一遍
./run.sh again safe                       # 容器留着，只跑一种
./run.sh clean                            # 删容器
```

`MigrationLab` 一轮演练：旧表 20 万行 → 打开双写（4 个线程改状态、30% 打在 200 个热点订单上；1 个线程下新单；写新库千分之二失败只记日志）→ 分批搬运（每批 5000、批间停 30 ms）→ 停流量 → 分块校验（`COUNT` + `BIT_XOR(CRC32(...))`，新库 8 张表的结果相加 / 异或后和旧表比）→ 逐行比对、以旧表为准修复 → 再校验 → 按用户灰度 10% 切读并影子对比 → 回滚开关。

- naive：双写普通 upsert、搬运 upsert 覆盖。
- safe：双写版本保护（`IF(VALUES(version) > version, ...)`，version 最后改）、搬运 `INSERT IGNORE`。

## 预期输出

2 核 ARM 虚拟机，JDK 17，ShardingSphere 5.5.3，MySQL 8.4.13，各跑一次：

```
naive：搬运 4073 行/秒；改状态 7024、新单 2044、写失败 18；第 1 次校验 16/21 块不对；缺行 3、状态倒退 95；修复 98 行后 0/21
safe ：搬运 6532 行/秒；改状态 4967、新单 1460、写失败 7； 第 1 次校验 1/21 块不对；缺行 2、状态倒退 0；修复 2 行后 0/21
切读 10%：对比不一致 0
```

线程调度每次不同，数字会变；naive 的“状态倒退”是几十到上百，safe 应该是 0。

## 练习

1. safe 里双写保留版本保护、搬运改回 upsert 覆盖，再跑一次；反过来再跑一次，分开量两种竞态各贡献多少。
2. 校验只算 `COUNT(*)`，看 naive 那一轮第 1 次校验报几块。
3. 把校验挪到停流量之前，隔 2 秒重比一次。
4. 给 Repository 加 `writePrimary = NEW` 分支（先写新表、反向写旧表），切写后再校验。
