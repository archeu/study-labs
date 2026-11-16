# 137、143 和那份没加密的密码：Linux 信号、启动脚本、配置和密钥

对应页面：[137、143 和那份没加密的密码：Linux 信号、启动脚本、配置和密钥](https://study.ainiya.de/cloudnative/linux-config-secrets.html)（J14 第 6 个知识点，全景第 4、9、11 站）

没有 Java 代码。镜像用 `../docker-image-layers` 构建的 `lab/order-svc-layered:1.8.3` 和 `busybox:1.37.0`，集群用 `../k8s-core-objects/cluster.sh`（kind v0.33.0，Kubernetes v1.37.0）。`run.sh` 本身也是脚本写法的示范：`set -euo pipefail`、`trap … INT TERM` 收尾。

| `k8s/` | 实验 |
|---|---|
| `pid1.yaml` | 同一镜像三种启动命令：`sh -c "java …"`、`sh -c "exec java …"`、busybox 的 `trap` 脚本 |
| `config.yaml` | 同一个 ConfigMap：环境变量、整个目录挂载、subPath 挂单个文件 |
| `secret.yaml` | Secret 同时挂成文件和环境变量 |

第 4 步用 `kubectl debug --target` 起一个 busybox 临时容器，和 java 共享进程空间。

## 怎么跑

```bash
./run.sh          # 约 4 分钟，结果在 out/results.txt
./run.sh clean
```

## 预期输出

2026-11-16，2 核 ARM（aarch64），kind v0.33.0 / Kubernetes v1.37.0：

```
pid1-shell：1 号进程「sh -c java $JAVA_OPTS -jar order-svc.jar」，删除用了 30.6 s
pid1-exec： 1 号进程「java -jar order-svc.jar」，删除用了 0.6 s
pid1-trap： 1 号进程「sh -c trap …」，删除用了 2.3 s
patch 之后约 60 s（另一次 67 s），目录挂载的文件变成 on；现在：env=off dir=on subpath=off
data.password：czNjcjN0LXB3 → base64 -d：s3cr3t-pw；挂载类型 tmpfs；etcd 里直接搜到明文
应用容器里的工具：ps=有 curl=有 jcmd=无 jstack=无 ss=无；临时容器里 ps 看到 PID 1 是 java（uid 10001）
```

## 练习

先猜结果，再运行（“做对了会看到什么”见页面 ⑥）。

1. 写一个 `set -euo pipefail` + `exec java` 的 entrypoint.sh，删除 Pod 降到 2 秒以内。
2. 用临时容器看 `/proc/1/status` 的 `SigCgt`，对比 sh 和 java 当 1 号进程。
3. 给 Pod 模板加 `checksum/config` 注解，改配置就触发滚动。
4. 密码从环境变量换成文件 + `spring.config.import=optional:configtree:/etc/secrets/`。
5. 用 kind 的 `kubeadmConfigPatches` 给 API Server 配 EncryptionConfiguration，etcd 里看到 `k8s:enc:aescbc:v1:`。
