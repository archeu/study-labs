# tc-1 断网了，谁来接着拍板：Raft 选主与日志复制

对应页面：[tc-1 断网了，谁来接着拍板：Raft 选主与日志复制](https://study.ainiya.de/arch/raft-consensus.html)

交易靶场 J09 全景“一次下单跨三个库”的第 3、7 站：Seata TC 是 3 个节点的 Raft 集群，开全局事务、写“要提交”的决议，都要先写进多数派。这里有两个程序：

- `MiniRaft`：300 行的 Raft，只有选主和日志复制。不起线程不开端口，消息和定时器放在按“虚拟毫秒”排序的事件队列里单线程执行，每次输出一模一样，方便对照页面上的分步图。时间参数取 SOFAJRaft 的量级（选举超时 1000 ms + 随机 0~1000 ms，心跳 100 ms，leader 租约 900 ms，网络单程 5 ms）。只要 JDK。
  - `elect`：3 个节点同时启动，谁先超时谁参选
  - `replicate`：全景第 3、7 站各写一条记录，过半就提交
  - `partition`：tc-1（leader）断网：它那条 BEGIN 9528 写不进，900 ms 后自己退位；另一边选出新 leader；网络恢复后 9528 被覆盖
  - `restrict`：tc-3 宕机时错过两条已提交记录，tc-1 一挂它先参选，tc-2 不投它（投票限制）
  - `split`：去掉随机，3 个节点一直 1:1:1 平票；恢复随机后 1000 次全部第 1 轮选出
  - `rejoin`：tc-3 断网 5 秒再回来，关 / 开 PreVote 对比
- `JRaftLab`：用 **SOFAJRaft 1.3.14**（Seata 2.6.0 的 TC 依赖的版本）起 3 个独立进程 `JRaftNode`，量冷启动选主、过半提交耗时、`kill -9` 和 SIGTERM 杀 leader 后多久选出新 leader、杀掉两个 follower 后 leader 多久退位。端口 18081~18083，数据写到参数给的目录。

按 JDK 8 写法（`--release 8`）。

## 怎么跑

```bash
cd java
mvn -q -pl arch/raft-consensus compile
java -cp arch/raft-consensus/target/classes labs.arch.raft_consensus.MiniRaft all   # 或 elect | replicate | partition | restrict | split | rejoin

# JRaftLab 要自己拉起子进程，classpath 得是完整的（不要用 exec:java）
mvn -q -pl arch/raft-consensus dependency:build-classpath -Dmdep.outputFile=target/cp.txt
cd arch/raft-consensus
java -cp target/classes:$(cat target/cp.txt) labs.arch.raft_consensus.JRaftLab /tmp/jraft-data 3
rm -rf /tmp/jraft-data
rm -f /tmp/librocksdbjni*.so   # JRaft 的 RocksDB 每个进程解压一份 13 MB 的本地库，被 kill -9 的进程不会自己删
```

IDE 里：`MiniRaft` 参数 `all`；`JRaftLab` 参数 `<数据目录> 3`（IDE 的 classpath 是完整的，可以直接跑）。

## 预期输出

本机 2 核 ARM、11 GB 内存，JDK 17.0.21。

`MiniRaft partition`（节选，每次一样）：

```
  t= 1300 ms  ----- tc-1 断网
  t= 1300 ms  tc-1  追加 index 4（t1:BEGIN 9528），发给 follower，等过半
  t= 2210 ms  tc-1  已经 900 ms 联系不上多数派 → 自己退位
  t= 2383 ms  tc-2  等了 1128 ms 没心跳 → 任期 2，投自己，向其他节点要票（我最后一条 index=3 term=1）
  t= 2388 ms  tc-3  投给 tc-2
  t= 2393 ms  tc-2  拿到 2/3 票 → 任期 2 的 leader
  ...
  t= 4398 ms  tc-1  index 4 起和 leader 冲突，删掉 [t1:BEGIN 9528]
```

`MiniRaft rejoin`：

```
  关 PreVote 断网期间 tc-3 的任期涨到 5；回来后 leader 换了 1 次，没有 leader 的时间约 1080 ms，最后 leader=tc-1 任期=6
  开 PreVote 断网期间 tc-3 的任期涨到 1；回来后 leader 换了 0 次，没有 leader 的时间约 0 ms，最后 leader=tc-1 任期=1
```

`JRaftLab`，跑了 2 次，下面是第 1 次；第 2 次 kill -9 三轮是 1322 / 2100 / 1078 ms，优雅停机 75 / 89 / 91 ms，退位 910 ms：

```
[1] 冷启动：3 个 JVM 起好用了 1898 ms；最后一个起好之后 1338 ms，tc-2 当选，任期 1
[2] leader tc-2 每 20 ms 提交一条，3 秒共 151 条：过半提交 p50 = 7.05 ms，p99 = 25.71 ms，最大 58.08 ms
[3] kill -9 当前 leader，3 轮：
  第 1 轮：杀 tc-2 → 1631 ms 后 tc-1 当选（任期 2）；写不进的窗口 1655 ms
  第 2 轮：杀 tc-1 → 1104 ms 后 tc-2 当选（任期 3）；写不进的窗口 1114 ms
  第 3 轮：杀 tc-2 → 1295 ms 后 tc-3 当选（任期 4）；写不进的窗口 1318 ms
[3] SIGTERM 优雅停机 当前 leader，3 轮：
  第 1 轮：杀 tc-3 →   87 ms 后 tc-1 当选（任期 5）；写不进的窗口  119 ms
  第 2 轮：杀 tc-1 →   99 ms 后 tc-2 当选（任期 6）；写不进的窗口  128 ms
  第 3 轮：杀 tc-2 →   86 ms 后 tc-1 当选（任期 7）；写不进的窗口  110 ms
[4] kill -9 两个 follower [tc-2, tc-3]：leader tc-1 在 964 ms 后退位（Majority of the group dies: 2/3）；之后提交成功 0 条
```

## 练习

先猜结果，再运行。

1. 在 `MiniRaft.onVoteReq` 里把 `upToDate` 改成恒为 `true`，跑 `restrict`。tc-3 当选以后，已经提交的 `BEGIN 9528`、`COMMITTING 9528` 还在吗？对应到 Seata，是哪一单出了什么事？
2. 删掉 `becomeLeader` 里追加 no-op 的那一行，跑 `partition`。tc-2 当选后，index 3 的 `COMMITTING 9527` 在它那里什么时候才算提交？如果断网期间一直没有新的下单呢？
3. 把 `JRaftNode` 的 `setElectionTimeoutMs(1000)` 改成 300，再跑 `JRaftLab`。kill -9 后写不进的窗口缩到多少？再想：生产上一次 300 ms 的 Full GC 会发生什么。
4. `MiniRaft.rejoin` 里把断网时间从 5000 改成 20000（PreVote 关）。tc-3 回来时任期是多少？不可用的时间变了吗，为什么？
