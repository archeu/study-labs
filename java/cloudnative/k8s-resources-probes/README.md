# 装得下、活着、能干活：requests、limits、探针和 HPA

对应页面：[装得下、活着、能干活：requests、limits、探针和 HPA](https://study.ainiya.de/cloudnative/k8s-resources-probes.html)（J14 第 3 个知识点，全景第 6、8、10 站）

order-svc 的“埋雷版”（Spring Boot 3.5.16）：`POST /api/debug/hog?mb=32` 每次留住 32 MB，`GET /api/burn?ms=100` 忙算 100 毫秒，`GET /api/jvm` 看 JVM 认到的堆和 CPU，环境变量 `STARTUP_DELAY_SEC` 让它启动前先睡一会儿。`Dockerfile` 和上一页的多阶段四层写法一样，模块不继承 `java/pom.xml`。

| `k8s/` | 实验 |
|---|---|
| `big-request.yaml` | requests 写成 16Gi，看 Pending 和调度事件 |
| `oom-xmx.yaml` / `oom-pct.yaml` | limit 384Mi，`-Xmx384m` 对 `MaxRAMPercentage=75`：OOMKilled 还是 Java OOM |
| `slow-liveness.yaml` / `slow-startup.yaml` | 启动 40 秒：只配 liveness 对 加 startupProbe |
| `hpa.yaml` + `load.yaml` | CPU 目标 50% 的 HPA，4 路并发压测 |
| `toolbox.yaml` | 集群里发请求用的 busybox |

## 怎么跑

集群用 `../k8s-core-objects/cluster.sh`（kind v0.33.0，Kubernetes v1.37.0），第一次会装 metrics-server v0.9.1 并加 `--kubelet-insecure-tls`。

```bash
./run.sh          # 约 8 分钟，结果在 out/results.txt
./run.sh clean
```

## 预期输出

2026-11-16，2 核 ARM（aarch64），kind v0.33.0 / Kubernetes v1.37.0：

```
big-request：Pending；… 2 Insufficient memory
oom-xmx：JVM 堆上限 371 MB；第 8 次 hog 时连接断开 → OOMKilled exit=137 restarts=1，日志里 OutOfMemoryError 0 次
oom-pct：JVM 堆上限 278 MB；第 8 次 hog 回 500 → restarts=0 Running，日志里 java.lang.OutOfMemoryError: Java heap space，普通接口照常回 200
slow-liveness：100 秒里 restarts=5，事件 Liveness probe failed … connection refused
slow-startup：restarts=0，48 s Ready
HPA：31 s 时 786%/50% → 1→5；撤压后 1%，稳定窗口 30 s 过后 5→1
```

## 练习

先猜结果，再运行。

1. 给 `oom-pct.yaml` 加 `-XX:+ExitOnOutOfMemoryError`：重启次数、退出码、日志各是什么？
2. 加 `-XX:NativeMemoryTracking=summary`，`jcmd 1 VM.native_memory summary` 看堆外到底多少。
3. 删掉 HPA 的 `stabilizationWindowSeconds`（默认 300），压测中途停 60 秒再压，副本数会不会掉？
