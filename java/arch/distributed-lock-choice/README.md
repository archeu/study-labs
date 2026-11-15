# 两个实例只许一个发：Redis、ZooKeeper、数据库三种分布式锁怎么选

对应页面：[两个实例只许一个发：Redis、ZooKeeper、数据库三种分布式锁怎么选](https://archeu.github.io/study-notes/arch/distributed-lock-choice.html)

交易靶场 J09 全景“一次下单跨三个库”的第 9 站：两个订单服务实例每秒扫 outbox，抢到锁的那个把 `ORDER_CREATED 9527` 发到 Kafka。
“发到 Kafka”用往 `kafka_log` 表插一行代替（另一条连接、自动提交，和 Kafka 一样不跟着业务事务回滚）。
MySQL 8.4 跑在 Docker 里；ZooKeeper 用 Curator 的 `TestingServer` / `TestingCluster` 在 JVM 里起，不用另装。按 JDK 8 写法（`--release 8`）。

`LockLab` 三段：

- `scan`：两个实例（两个线程）同时扫，5 轮，每轮新来 2 单。不加锁 / `FOR UPDATE` 锁一行 / 租约行（5 秒）/ ZooKeeper `InterProcessMutex` / `FOR UPDATE SKIP LOCKED`（不用锁）
- `die`：持锁的 A 是**单独的 JVM**，读完 9527 后打印 READY，主进程对它 `kill -9`、`kill -STOP` 8 秒再 `kill -CONT`，或者什么也不做（A 自己发 8 秒）。B 在主进程里抢锁，看等了多久、9527 发了几次
- `failover`：3 节点 ZooKeeper，A 持锁时杀 leader，再杀一台（凑不够多数派），打印 A 的连接状态和 `isAcquiredInThisProcess()`

## 怎么跑

```bash
cd java/arch/distributed-lock-choice
./run.sh new scan        # 起 MySQL 容器（127.0.0.1:33096），跑 scan；参数二可以是 scan | die | failover | all
./run.sh again die       # 容器留着，只重跑
./run.sh clean           # 删容器
# 本机不在 docker 组里：DOCKER="sudo docker" ./run.sh new all
```

`die` 会用 `java.class.path` 起子进程，所以要用 `run.sh`（`java -cp`）跑，不要用 `mvn exec:java`。

## 预期输出

2 核 ARM · JDK 17.0（`--release 8` 编译）· MySQL 8.4.13 · Curator 5.9.0 + ZooKeeper 3.9.3：

```
== scan：两个实例同时扫 outbox，5 轮，每轮新来 2 单，send 一次 50 ms
写法                     | 发出 | 重复 | A 发 | B 发 | 没抢到锁、直接回去（次）
不加锁                    |   20 |   10 |   10 |   10 | 0
FOR UPDATE 锁一行         |   10 |    0 |    6 |    4 | 0
租约行（5 秒）               |   10 |    0 |    8 |    2 | 5
ZooKeeper 临时顺序节点       |   10 |    0 |    2 |    8 | 5
SKIP LOCKED（不用锁）       |   10 |    0 |    5 |    5 | 0
== die：持锁的 A（单独的 JVM）读完 outbox 里的 9527 之后出事，B 每 100 ms～1 s 抢一次锁
锁           | A 怎么了                  | B 等了多久拿到锁 | 9527 发了几次（谁, token） | A 醒来时还以为持锁吗
FOR UPDATE   | kill -9                | 0.0 s          | 1 次（B）                   | （已死）
FOR UPDATE   | kill -STOP 卡 8 秒       | 8.1 s          | 1 次（A）                   | 以为还持着
FOR UPDATE   | 活着，但发得慢（8 秒）           | 8.1 s          | 1 次（A）                   | 以为还持着
租约行 5s       | kill -9                | 5.0 s          | 1 次（B#2）                 | （已死）
租约行 5s       | kill -STOP 卡 8 秒       | 5.0 s          | 2 次（B#2, A#1）            | 以为还持着
租约行 5s       | 活着，但发得慢（8 秒）           | 5.0 s          | 2 次（B#2, A#1）            | 以为还持着
ZK 会话 5s     | kill -9                | 5.1 s          | 1 次（B#1）                 | （已死）
ZK 会话 5s     | kill -STOP 卡 8 秒       | 5.3 s          | 2 次（B#3, A#2）            | 以为还持着
ZK 会话 5s     | 活着，但发得慢（8 秒）           | 8.1 s          | 1 次（A#4）                 | 以为还持着
== failover：3 节点 ZooKeeper，A 持锁时先杀 leader，再杀一台
会话超时协商结果：5000 ms
A 持锁，节点 /locks/outbox-scan/_c_568b3b47-ab82-45aa-917d-a91046faf46d-lock-0000000000（token 0），A 连在 server 3，leader 是 server 3
[  1.7 s] 杀掉 leader server 3
  [  1.8 s] A 的连接状态 → SUSPENDED
  [  2.4 s] A 的连接状态 → RECONNECTED
3 秒后：新 leader 是 server 2，锁节点还在吗：true，B 再抢 2 秒：抢不到
[  6.8 s] 再杀 server 2，只剩 1 台，凑不够多数派
  [  6.8 s] A 的连接状态 → SUSPENDED
B 抢锁：4 秒没有返回（连不上，卡在重试里）
  [ 11.9 s] A 的连接状态 → LOST
[ 16.8 s] A 自己以为还持锁吗（isAcquiredInThisProcess）：true
```

A、B 各发几条每次不同；发出、重复两列，和 `die` 里每格的次数是稳定的。时间会差零点几秒。

## 练习

先猜结果，再运行。

1. 给 ZooKeeper 版的 A 加 `ConnectionStateListener`：收到 SUSPENDED 或 LOST 就设标志，`send` 前检查。再跑 `die` 的“冻住 8 秒”，9527 还发 2 次吗？（A 冻住时监听线程也冻着，醒来后哪个先跑？）
2. 给租约行加续期线程：每 1.5 秒 `UPDATE lease SET expire_at = NOW(3) + INTERVAL 5 SECOND WHERE name=? AND owner=? AND token=?`，影响 0 行就停发。“活着但慢”和“冻住”哪一种变成了 1 次？
3. outbox 加 `fence` 列，标 SENT 改成 `WHERE id=? AND fence < ?`，token 用租约行的 token。“冻住 8 秒”时 A 醒来那次的标记还能写进去吗？那次发送呢？
