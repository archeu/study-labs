# 部署：容器、扩容、密钥管理、私有化

对应页面：[部署：容器、扩容、密钥管理、私有化](https://study.ainiya.de/agent/agent-deploy.html)

全景“大促夜里 1000 个告警同时涌进来”第 ⑪ 站：把 worker 装进容器，看镜像大小、key 会不会进镜像、`docker stop` 时 worker 怎么退。

| 文件 | 做什么 |
|---|---|
| `app/worker.py` | 从 Redis Stream 领任务，每轮睡 `ROUND_SEC` 秒（假的模型调用）、写检查点；收到 SIGTERM 做完这一轮、不 XACK 就退出（`GRACEFUL=0` 时不装处理函数） |
| `Dockerfile` | 多阶段：uv 按锁文件装依赖，slim 基础镜像，非 root，exec 形式 CMD |
| `Dockerfile.naive` | 反面教材：完整基础镜像、pip、key 写进 `ENV`、shell 形式 CMD |
| `k8s/worker.yaml` | Secret、Deployment（90 秒宽限期、非 root、只读根文件系统）、KEDA ScaledObject（按 `lagCount`，上限 2）、NetworkPolicy（只许连网关和 Redis）。**按文档写的，没有在集群上跑过** |
| `demo.py` | 镜像大小、`docker history` 里的 key、三次 `docker stop` |

## 怎么跑

```bash
cd agent/agent-deploy
sudo docker build -f Dockerfile.naive -t s10-worker:naive .
sudo docker build -t s10-worker:slim .
sudo docker run -d --name s10-redis -p 127.0.0.1:63710:6379 redis:8.12.0-alpine
cd .. && uv run python agent-deploy/demo.py      # 约 1 分钟
```

## 预期输出

2026-11-28，2 核 ARM，Docker 27.3.1：

```
== 1 镜像大小
   s10-worker:naive  1.14GB
   s10-worker:slim   153MB
== 2 密钥有没有进镜像（docker history 里能不能看到）
   naive ：ENV GATEWAY_KEY=vk-pay-19c2-demo-not-real
   slim  ：没有
== 3 docker stop：worker 正在跑第 2 轮
slim 镜像，exec 形式 CMD，装了 SIGTERM 处理
   1 号进程：python worker.py
   docker stop 用了 2.1 秒，退出码 0；日志：… → round_start#2 → SIGTERM → round_done#2 → exit_graceful#2
slim 镜像，exec 形式 CMD，没装 SIGTERM 处理
   docker stop 用了 10.2 秒，退出码 137；日志：… → round_done#4 → round_start#5
naive 镜像，shell 形式 CMD，装了 SIGTERM 处理
   1 号进程：/bin/sh -c python worker.py
   docker stop 用了 10.1 秒，退出码 137；日志：… → round_done#4 → round_start#5
```

三种情况下任务都还在待确认列表里（没有 XACK），别的 worker 能用 XAUTOCLAIM 接手。

用完删掉自己建的镜像：`sudo docker rmi s10-worker:naive s10-worker:slim python:3.13 python:3.13.18-slim-bookworm ghcr.io/astral-sh/uv:0.13.10`（只删自己拉的）。

## 练习

1. 只把 naive 的最后一行改成 `CMD ["python", "worker.py"]`：先猜会不会变成 2 秒正常退出。
2. `ROUND_SEC=15`、`docker stop -t 5`：有处理函数也会被强杀吗？检查点停在第几轮？
3. naive 镜像加 `docker run --init`：1 号进程变成了什么？SIGTERM 能不能传到 python？
4. `docker run --read-only` 跑 slim 镜像：worker 能不能起来？
