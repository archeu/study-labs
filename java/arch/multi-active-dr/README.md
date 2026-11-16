# 多活与容灾：同城双活、异地多活、RTO 和 RPO

对应页面：[多活与容灾：同城双活、异地多活、RTO 和 RPO](https://study.ainiya.de/arch/multi-active-dr.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.arch.multi_active_dr` 包下的 main。
- 需要 docker：程序自己起、自己删两个 `redis:8.10.3-alpine` 容器（端口 63801、63802，网络 dr-net）；本机不在 docker 组里就加 `DOCKER="sudo docker"`。
- 命令行：`cd java && mvn -q -pl arch/multi-active-dr compile exec:java -Dexec.mainClass=labs.arch.multi_active_dr.<类名>`

## 预期输出

2 核 ARM 虚拟机，JDK 17，Lettuce 6.8.2，Redis 8.10.3（次数每次不同，“async 丢、wait 不丢”每次成立）：

```
async 断网前已确认 2427；客户端最后收到“成功”的是第 14356 次；断网后被告知失败 1 次；新主 dr-b 上 seq = 2801 → 已确认却丢了 11555 次写入
wait  断网前已确认 1648；客户端最后收到“成功”的是第 1987 次；断网后被告知失败 9 次；新主 dr-b 上 seq = 1988 → 已确认却丢了 0 次写入
```

“专线断了”用 ACL 模拟：dr-b 以用户 `repl` 复制 dr-a，断链时 `ACL SETUSER repl off` + `CLIENT KILL TYPE replica`，不碰网络。

## 练习

先猜结果，再运行。

1. “再写 2 秒”改成 0.5 秒和 5 秒，async 丢失的次数和时长成正比吗？
2. `WAIT 1 20` 和 `WAIT 1 2000`，断开期间各尝试几次？
3. 断链 5 秒后恢复 `repl` 用户、不杀主，dr-b 能否追平？
4. 从杀掉 dr-a 到在 dr-b 上第一次写成功，计时得到 RTO。
