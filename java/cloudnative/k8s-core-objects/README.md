# 只说想要什么：Kubernetes 核心对象与控制循环

对应页面：[只说想要什么：Kubernetes 核心对象与控制循环](https://archeu.github.io/study-notes/cloudnative/k8s-core-objects.html)（J14 第 2 个知识点，全景第 5、9、11 站）

没有 Java 代码：一个 kind 集群配置、order-svc 的 Deployment + Service、一个 busybox 客户端，和一个按顺序做实验的脚本。镜像用上一页 `../docker-image-layers` 构建的 `lab/order-svc-layered:1.8.3` 和 `1.8.4`。

| 文件 | 作用 |
|---|---|
| `kind-cluster.yaml` | 本机“交易靶场集群”：1 个控制面 + 2 个工作节点 |
| `cluster.sh` | `up` 建集群（kindest/node:v1.37.0），`load` 装镜像，`down` 删集群；J14 后面几页共用 |
| `k8s/order-svc.yaml` | Deployment（4 副本，maxSurge 1 / maxUnavailable 0，readiness 探针）+ Service |
| `k8s/client.yaml` | 集群里的“用户”：每 50 ms 经 Service 调一次，打印 ok / fail |
| `run.sh` | 六步：apply、删 Pod、改标签、滚动到 1.8.4、rollout undo、手动 scale 后再 apply |

## 怎么跑

要 kind v0.33.0、kubectl v1.37（放进 PATH，或者 `KIND=`、`KUBECTL=` 指过去），docker 默认用 sudo。

```bash
(cd ../docker-image-layers && ./run.sh)   # 先构建两个镜像
./cluster.sh up                           # 约 2 分钟
./run.sh                                  # 约 3 分钟，结果在 out/results.txt
./run.sh clean                            # 删本页的对象，集群留着
./cluster.sh down                         # J14 学完再删
```

## 预期输出

2026-11-16，2 核 ARM（aarch64），kind v0.33.0，Kubernetes v1.37.0：

```
从 apply 到 4/4 Ready：19.6 s
删除一个 Pod → 0.3 s 后新 Pod 被创建 → 7.1 s 后它 Ready；client 0 次失败
改标签的 Pod 还在 Running；Service 就绪地址 3 个；ReplicaSet 又补了一个
滚动 1.8.3 → 1.8.4 用了 26.4 s，4 轮；client 317 次成功，4 次失败（没有 preStop）
rollout undo 用了 23.8 s；kubectl 提示回滚不会更新 last-applied-configuration；client 3 次失败
手动 scale 到 2，再 apply → spec.replicas=4，镜像回到 YAML 里的 1.8.3
```

两次运行的失败次数在 3～6 之间浮动。

## 练习

先猜结果，再运行。

1. 给容器加 `lifecycle.preStop.exec.command: ["sleep","5"]`，重跑：滚动时还有失败吗？
2. 删掉 `strategy`（用默认 25% / 25%），再发一次，看 `out/rollout.txt` 里 `endpoints` 最少到几。
3. 再建一个 1 副本的 `order-svc-canary`（镜像 1.8.4，标签 `app: order-svc` + `track: canary`），client 拿到 1.8.4 的比例大约是多少？
