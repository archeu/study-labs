# 给 agent 的命令造个笼子：Linux 进程、权限与容器隔离

对应页面：[给 agent 的命令造个笼子：Linux 进程、权限与容器隔离](https://study.ainiya.de/cloudnative/linux-process-isolation.html)（AI 地图站 04 的补给页）

把一个“模型提议的命令”关进越来越小的笼子：普通子进程 → 换用户 + rlimit + timeout → namespace → 容器。每层跑几条 shell 命令，打印退出码和输出第一行。

## 怎么跑

需要 Linux。第 3 层要求内核允许非特权 user namespace；第 4 层要 docker（命令里带了 `sudo`）和 `alpine:3.20`。不需要 uv，也不需要模型。

```bash
sudo docker pull alpine:3.20
cd agent && python3 linux-process-isolation/main.py            # 四层
python3 linux-process-isolation/main.py --layer 3              # 只跑一层
```

## 预期输出

2026-11-24，Ubuntu 22.04.5，内核 6.8.0，Docker 27.3.1：

```
第 1 层：ls ~ 成功；touch ~/agent-was-here 成功；curl HTTP 200；cat /etc/shadow Permission denied
第 2 层：timeout 2 sleep 10 → 124；prlimit --as=200MB 申请 500MB → MemoryError；nobody 写家目录 → Permission denied
第 3 层：PID ns 里 ps 只剩自己；NET ns 只有 lo DOWN、连不上；user ns 里 id -u = 0，cat /etc/shadow 仍被拒
第 4 层：uid=65534(nobody)；联网失败；touch /x Read-only file system；fork 炸弹 can't fork（退出码 2）；吃 200MB → 137 Killed
```

启动开销：`docker run --rm alpine true` 约 0.3 秒；`unshare` 不到 10 毫秒。

## 练习

先猜结果，再运行。

1. 第 4 层去掉 `--user`，跑 `id`；再挂一个不带 `:ro` 的临时目录写文件，回宿主看文件归谁。
2. `subprocess.run("sleep 100 & sleep 100", shell=True, timeout=2)` 超时后 `ps` 看残留；改 `start_new_session=True` + `os.killpg`。
3. `FOO=secret` 下用 subprocess 跑 `env`，再加 `env={"PATH": "/usr/bin:/bin"}` 对比。
