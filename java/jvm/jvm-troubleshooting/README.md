# JVM 故障排查：OOM、频繁 Full GC、CPU 飙高

对应页面：[JVM 故障排查：OOM、频繁 Full GC、CPU 飙高](https://archeu.github.io/study-notes/jvm/jvm-troubleshooting.html)（J03 全景第 9 站）

交易靶场订单服务的缩小版，用 JDK 自带的 `com.sun.net.httpserver` 起三个接口，每个埋一个雷。

| 类 | 做什么 |
|---|---|
| `TradeServer` | 8 个 `http-exec` 线程。`/order` 每单放进一个只放不删的 `static Map RECENT`（雷 1：泄漏）；`/export?rows=200000` 把订单拼成一个大字符串再写出（雷 2：大对象，`&stream=true` 是流式改法）；`/coupon?cents=1&prices=29900,29900` 按比例分摊优惠券，1 分钱分不出去就死循环（雷 3，`&fixed=true` 是改法） |
| `Order` | 订单，新下的单带 1 KB 快照 |
| `Coupon` | `split`（有 bug）和 `splitFixed`（尾差给最后一件） |
| `Load` | 压测客户端：`Load <url> <并发> <秒数>`，每 5 秒打印成功数，最后打印 p50 / p99 / 最大延迟 |

服务参数（`-D`）：`port=8090`，`historyK=200` 常驻多少千条历史订单（导出从这里取），`threads=8`。务必加 `-Dsun.net.httpserver.nodelay=true`，不然每个请求被 Nagle 拖 40 ms。

## 怎么跑

`--release 8` 编译，用 JDK 17 运行。每个雷开一个终端起服务，另一个终端压测，测完 `kill` 掉服务。

```bash
cd java && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 mvn -q -pl jvm/jvm-troubleshooting compile
cd jvm/jvm-troubleshooting
J=/usr/lib/jvm/java-17-openjdk-arm64/bin/java; CP=target/classes; M=labs.jvm.jvm_troubleshooting
ND=-Dsun.net.httpserver.nodelay=true

# 雷 1：泄漏到 OOM
$J -Xmx128m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=. -Xlog:gc:file=leak.log $ND -DhistoryK=0 -cp $CP $M.TradeServer
$J -cp $CP $M.Load "http://127.0.0.1:8090/order?user=1024&sku=88&qty=2" 8 30
grep 'Pause Full' leak.log | sed -n '1p;$p'; jcmd <pid> GC.class_histogram | head
# 堆转储用 Eclipse MAT 命令行（要 JDK 17+）：
ParseHeapDump.sh java_pid<pid>.hprof org.eclipse.mat.api:suspects   # 打开 *_Leak_Suspects.zip

# 雷 2：大导出 vs 流式
$J -Xmx256m -Xlog:gc:file=big.log $ND -DhistoryK=2000 \
   -XX:StartFlightRecording=disk=true,maxage=6h,maxsize=200m,dumponexit=true,filename=app.jfr -cp $CP $M.TradeServer
$J -cp $CP $M.Load "http://127.0.0.1:8090/export?rows=200000" 4 30
$J -cp $CP $M.Load "http://127.0.0.1:8090/export?rows=200000&stream=true" 4 30   # 另起一个服务再跑
top -H -b -n 2 -d 3 -p <pid>                    # 大导出时看谁吃 CPU
jcmd <pid> JFR.dump name=1 filename=x.jfr && jfr print --events jdk.ObjectAllocationSample x.jfr | less

# 雷 3：死循环
$J $ND -DhistoryK=0 -cp $CP $M.TradeServer
curl "http://127.0.0.1:8090/coupon?cents=1&prices=29900,29900" &
top -H -b -n 1 -p <pid> | head; printf '%x\n' <线程号>
jstack <pid> | grep -A3 'nid=0x<十六进制> '      # 隔 2 秒再拍一次
jcmd <pid> JFR.start duration=10s settings=profile filename=cpu.jfr
jfr print --events jdk.ExecutionSample --stack-depth 1 cpu.jfr | grep 'line:' | sort | uniq -c
```

## 预期输出（2 核 ARM，JDK 17.0.20.1，G1）

| 雷 | 看到的 |
|---|---|
| 1 泄漏 | 约 7000 单/秒，第 16 秒起 Full GC，6 秒里 131 次，从 `124M->117M` 到 `125M->125M`；OOM 后进程还在、端口在听，请求不回；转储 140 MB 写 0.249 s；直方图第一行 `[B` 116.7 MB；MAT 第一嫌疑 `TradeServer.RECENT` 拉着 128.7 MB（98.5%） |
| 2 大导出 | 30 秒 401 次 Full GC、累计 20.8 s，336 个请求 9 个 OOM，最慢 30.6 s；GC 后中位数 193M（最低 144M）；`top -H` 里两个 GC 线程 72%、71%，业务线程各 4%；JFR 分配采样 97% 在导出路径 |
| 2 流式 | 0 次 Full GC，12 次 Young GC，970 个请求 0 错误，GC 后稳定在 108M |
| 2 大导出 + 1 GB 堆 | 0 次 Full GC，514 次 Humongous 触发的 Young GC，1603 个请求 |
| 3 死循环 | `top -H` 一个 `http-ex+` 线程 93.8%；jstack `http-exec-5 … cpu=5587ms elapsed=5.64s … at Coupon.split(Coupon.java:19)`，2 秒后 cpu=7793ms 同一行；JFR 10 秒 983 个样本全在 `Coupon.split`（行号 14 和 19）；其他请求照常 2 ms |

每次跑的数字会有波动，量级和结论应该一样。

## 练习

先猜结果，再运行。

1. 把 `RECENT` 换成有上限的 LRU（`LinkedHashMap` 重写 `removeEldestEntry`，上限 1 万），压 60 秒，用 GC 日志证明不再 Full GC。
2. 雷 1 加上 `-XX:+ExitOnOutOfMemoryError` 重跑，看退出码（应为 3）和转储文件。
3. 同时发 8 个一分钱请求，`/order` 还能返回吗？然后给 `split` 加最大轮数，写边界单测。
