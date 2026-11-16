# 故障演练与预案

对应页面：[故障演练与预案](https://study.ainiya.de/arch/chaos-drill-runbook.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.arch.chaos_drill_runbook` 包下的 main。
- 需要 docker：程序自己起、自己删 1 主 2 从 3 哨兵（`redis:8.10.3-alpine`，网络 ch-net，客户端用容器 IP 直连，Linux 主机可用）；本机不在 docker 组里就加 `DOCKER="sudo docker"`。参数 `-Dexec.args="30000 5000"` 指定要演的 down-after-milliseconds。
- 命令行：`cd java && mvn -q -pl arch/chaos-drill-runbook compile exec:java -Dexec.mainClass=labs.arch.chaos_drill_runbook.<类名>`

## 预期输出

2 核 ARM 虚拟机，JDK 17，Lettuce 6.8.2，Redis 8.10.3：

```
== down-after-milliseconds = 30000 ==
3 观察：杀主后 42.1 秒恢复写入；期间失败 81 次；单次请求最慢 523 ms（超时设 500 ms）
        新主上 seq = 1158，客户端确认过的最大值 1158 → 丢失已确认写入 0 次
== down-after-milliseconds = 5000 ==
3 观察：杀主后 7.2 秒恢复写入；期间失败 14 次；单次请求最慢 524 ms（超时设 500 ms）
        新主上 seq = 1147，客户端确认过的最大值 1147 → 丢失已确认写入 0 次
5 恢复：旧主重启 10.2 秒后被降为从库（此刻 orphan = written-to-old-master），16.3 秒后和新主同步完成
        同步完成后，那段时间直连旧主写的 orphan：旧主上 (没了)，新主上 (没有)
```

## 练习

先猜结果，再运行。

1. `docker logs -f ch-s1` 记下 +sdown、+odown、+switch-master 的时间戳，把 7.2 秒拆开。
2. 先杀两个哨兵再杀主：会切换吗？
3. 先断复制（见 multi-active-dr）再杀主，RPO 还是 0 吗？
4. 主节点设 `min-replicas-to-write 1`，重演第 5 步，写 orphan 会被拒吗？
5. 加停止条件：失败超过 15 秒自动 `docker start ch-m` 并结束。
