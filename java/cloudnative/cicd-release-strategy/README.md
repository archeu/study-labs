# 换版本不断流、出事能秒回：CI/CD 与滚动、蓝绿、金丝雀

对应页面：[换版本不断流、出事能秒回：CI/CD 与滚动、蓝绿、金丝雀](https://study.ainiya.de/cloudnative/cicd-release-strategy.html)（J14 第 5 个知识点，全景第 1、4、11 站）

没有 Java 代码。镜像用 `../docker-image-layers` 构建的 `lab/order-svc-layered:1.8.3` / `1.8.4`，集群用 `../k8s-core-objects/cluster.sh`（kind v0.33.0，Kubernetes v1.37.0）。

| 文件 | 作用 |
|---|---|
| `k8s/rolling.yaml` / `rolling-prestop.yaml` | 4 副本滚动（maxSurge 1 / maxUnavailable 0），后者加 `lifecycle.preStop.sleep: 5` |
| `k8s/bluegreen.yaml` | blue（1.8.3）、green（1.8.4）各 4 个，Service 的 selector 决定流量去哪套 |
| `k8s/canary.yaml` | stable 4 个 + canary 1 个，Service 只按 `app` 选 |
| `k8s/client.yaml` | 集群里的用户：每 50 ms 调一次，打印拿到的版本号或 fail |
| `ci/release.yml` | 示例：GitHub Actions 流水线（测试 → 镜像 → 改部署仓库的 tag）。**没有启用、没有运行** |
| `ci/argocd-app.yaml` | 示例：Argo CD Application（automated + prune + selfHeal）。**本机没有安装 Argo CD** |

## 怎么跑

```bash
./run.sh          # 约 6 分钟，结果在 out/results.txt
./run.sh clean
```

## 预期输出

2026-11-16，2 核 ARM（aarch64），kind v0.33.0 / Kubernetes v1.37.0：

```
没有 preStop：1.8.3 → 1.8.4 用了 26.7 s；共 281 次：… fail=5
有 preStop（sleep 5）：1.8.3 → 1.8.4 用了 28.1 s；共 485 次：… fail=0
回滚（rollout undo，有 preStop）：用了 27.0 s；fail=0
蓝绿：同时在跑 8 个 Pod；切到绿、切回蓝都在 1 秒内连续拿到目标版本，fail=0
金丝雀：30 秒 506 次，1.8.4=100，新版占比 19.7%（按副本数应为 20%）
```

## 练习

先猜结果，再运行。

1. 把 preStop 的 sleep 改成 1 秒和 15 秒，失败数和滚动时间怎么变？最短多少秒能做到 0 失败？
2. 装一个 Gateway API 实现，用 HTTPRoute 给 1.8.4 配 5% 权重，和按副本数的 20% 对比。
3. 在 6 个副本、每个 23 秒启动的设定下，算出滚动回滚、蓝绿回切、撤掉金丝雀各要多久，再和页面实验台对一对。
