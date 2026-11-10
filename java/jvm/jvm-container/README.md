# 容器里的 JVM：内存和 CPU 限制

对应页面：[容器里的 JVM：内存和 CPU 限制](https://archeu.github.io/study-notes/jvm/jvm-container.html)

## 怎么跑

- IDE：打开仓库的 `java/pom.xml`，运行 `labs.jvm.jvm_container` 包下的 main。
- 命令行：`cd java && mvn -q -pl jvm/jvm-container compile exec:java -Dexec.mainClass=labs.jvm.jvm_container.<类名>`
- 要看到限额效果，得把 JVM 放进有限额的 cgroup。没有 Docker 时，Linux（cgroup v2）上可以用 systemd 临时建一个：

```bash
CP=target/classes   # 在 java/jvm/jvm-container 下
# 1. JVM 看到了什么：512 MB、半个核
sudo systemd-run --scope -q -p MemoryMax=512M -p CPUQuota=50% java -cp $CP labs.jvm.jvm_container.WhatJvmSees
# 2. 堆外越长越多：不限直接内存 → 被内核杀，echo $? 是 137
sudo systemd-run --scope -q -p MemoryMax=512M -p MemorySwapMax=0 java -Xmx450m -cp $CP labs.jvm.jvm_container.OffHeapCreep; echo $?
# 3. 同样的程序，给直接内存设上限 → JVM 自己抛 OutOfMemoryError，退出码 3
sudo systemd-run --scope -q -p MemoryMax=512M -p MemorySwapMax=0 java -Xmx450m -XX:MaxDirectMemorySize=64m -cp $CP labs.jvm.jvm_container.OffHeapCreep; echo $?
```

有 Docker 的话等价于 `docker run --memory=512m --cpus=0.5 ...`。

| 类 | 做什么 |
|---|---|
| `WhatJvmSees` | 打印 cgroup 的 `memory.max`、`cpu.max`，以及 JVM 据此算出的 CPU 数、最大堆、commonPool 并行度、选中的 GC |
| `OffHeapCreep` | 堆里放 200 MB 活对象，再每 200 ms 申请 16 MB 直接内存，每步打印 VmRSS |

## 预期输出

本机：2 核 ARM、12 GB、Ubuntu 22.04、cgroup v2，OpenJDK 17.0.20（`--release 8` 编译）。

```
# 1. MemoryMax=512M CPUQuota=50%
cgroup memory.max      = 536870912
cgroup cpu.max         = 50000 100000
availableProcessors    = 1
maxMemory (heap)       = 123 MB          # -XX:MaxHeapSize=128M，再扣一个 Survivor
GC                     = Copy / MarkSweepCompact /   # 不到 2 核或不到 1792 MB → SerialGC
# 同样的程序换成 MemoryMax=2G CPUQuota=200%：2 核、512 MB、G1
# 再加 -XX:-UseContainerSupport：堆变成 2984 MB（宿主 12 GB 的 25%），比容器还大

# 2. 不限直接内存
direct  272 MB, RSS=522324 kB
direct  288 MB, RSS=539324 kB
Killed
137      # dmesg: Memory cgroup out of memory: Killed process ... (java) anon-rss:522496kB

# 3. -XX:MaxDirectMemorySize=64m
JVM 自己报错了：java.lang.OutOfMemoryError: Cannot reserve 16777216 bytes of direct buffer memory (allocated: 50339840, limit: 67108864)
3
```

## 练习

先猜结果，再运行。

1. 把 `-Xmx450m` 换成 `-XX:MaxRAMPercentage=60`，重跑 2。堆的上限是多少？进程还会被杀吗？在第几次申请时出事？
2. 只给 `-p CPUQuota=150%`，`WhatJvmSees` 打印的 `availableProcessors` 是 1 还是 2？再加 `-XX:ActiveProcessorCount=4` 呢？
3. 把 `OffHeapCreep` 的直接内存换成起 300 个睡着的线程（每个 `-Xss1m`），在 512 MB 里会被杀吗？用 `-XX:NativeMemoryTracking=summary` 加 `jcmd <pid> VM.native_memory summary` 看 Thread 那一项实际占了多少。
