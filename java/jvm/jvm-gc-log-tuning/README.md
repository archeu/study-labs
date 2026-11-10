# GC 日志与调优：先定目标，再动参数

对应页面：[GC 日志与调优：先定目标，再动参数](https://archeu.github.io/study-notes/jvm/jvm-gc-log-tuning.html)（J03 全景第 8 站）

| 类 | 做什么 |
|---|---|
| `OrderService` | 交易靶场的下单接口压成一个进程：请求按固定速率到达，每个请求产生约 48 KB 临时对象，订单进 LRU 缓存；延迟从“计划到达时刻”算起（GC 期间排队的请求也算慢）。`-Dleak=true` 时每个请求再往一个只放不删的 static 列表塞 4 KB |
| `GcLogStats` | 读 `-Xlog:gc` / `-Xlog:gc*` 日志，算 GC 时间占比、停顿 p50 / p99 / 最长、各类停顿次数、分配速率、GC 后堆占用的走势，并给出结论：达标 / 参数问题 / 代码问题 |

参数（`-D`）：`rate=2000` 每秒请求数，`seconds=30`，`cacheK=900` 缓存里常驻多少千个订单，`tempKB=48`，`leak=false`。

## 怎么跑

`--release 8` 编译，用 JDK 17 运行。

```bash
cd java && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -q -pl jvm/jvm-gc-log-tuning compile
cd jvm/jvm-gc-log-tuning
J=/usr/lib/jvm/java-17-openjdk-arm64/bin/java; M=labs.jvm.jvm_gc_log_tuning

# 大促基线：G1 默认
$J -XX:+UseG1GC -Xms256m -Xmx256m -Xlog:gc*:file=g1.log -Drate=4000 -DcacheK=1600 -cp target/classes $M.OrderService
# 第 4 个参数填上一步打印的“压测从第几秒开始”，跳过预热
$J -cp target/classes $M.GcLogStats g1.log 50 5 1.4

# 换成 Parallel（同样的堆）
$J -XX:+UseParallelGC -Xms256m -Xmx256m -Xlog:gc*:file=par.log -Drate=4000 -DcacheK=1600 -cp target/classes $M.OrderService

# 泄漏：日常流量，堆 256 MB 和 512 MB 各跑一次
$J -XX:+UseG1GC -Xms256m -Xmx256m -Xlog:gc*:file=leak256.log -Dleak=true -Dseconds=120 -cp target/classes $M.OrderService
```

## 预期输出（2 核 ARM，JDK 17.0.20.1）

| 配置 | 最长停顿 | GC 占比 | Full GC | 请求 p99.9 |
|---|---|---|---|---|
| G1 默认 · 256 MB，大促 | 7.9 ms | 2.0% | 0 | 3.35 ms |
| Parallel · 256 MB，大促 | 308.6 ms | 98.4% | 172 | 6620 ms |
| Parallel · 768 MB，大促 | 37.9 ms | 0.7% | 0 | 19.08 ms |
| G1 · `MaxGCPauseMillis=5` | 5.5 ms | 2.0% | 0 | 3.77 ms |
| G1 · `-Xmn128m` | 15.6 ms | 1.4% | 0 | 5.14 ms |

- G1 基线的 GC 后堆占用稳定在 206～210 MB（≈ 活对象）；30 秒里 54 轮并发标记。
- Parallel 256 MB：老年代固定 171 MB（`ParOldGen ... (175104K)`），装不下 201 MB 活对象，每次 GC 都是 Full GC。
- 泄漏：256 MB 第 21.4 秒 OOM，512 MB 第 54.0 秒 OOM；`GcLogStats` 结论都是“Full GC 之后堆还是满的：回收不掉，是代码问题”。

每次跑的数字会有波动，量级和结论应该一样。

## 练习

先猜结果，再运行。

1. Parallel 跑大促流量，堆至少多大才不会一直 Full GC？先按“老年代 = 2/3 堆”算，再改 `-Xmx` 验证。
2. `-Dleak=true` 跑，在 OOM 之前 `jcmd <pid> GC.class_histogram | head`，看排第一的是什么。
3. 把 `LEAKED` 换成有上限的 LRU，再跑一次泄漏场景，用 `GcLogStats` 证明结论变回“达标”。
4. `-DcacheK=2000`、堆仍 256 MB：先猜三样数怎么变，再跑。
5. 启动参数换成 `-Xlog:gc*:file=gc-%p-%t.log:time,uptime,level,tags:filecount=10,filesize=50m`，连续启动两次，看旧日志变成了什么名字。
