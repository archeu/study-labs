# 沙箱：只读优先、命令白名单、隔离执行

对应页面：[沙箱：只读优先、命令白名单、隔离执行](https://study.ainiya.de/agent/agent-sandbox.html)

给值班助手加一个 `run_command`：模型给一行命令，过三道关（不过 shell、命令和选项白名单、路径限定在 /logs）后，在断网、只读、nobody、限内存和进程数的容器里执行，外面套超时。

| 文件 | 是什么 |
|---|---|
| `sandbox.py` | `check()` 三道关，`run_in_container()` 容器执行，`run_command()` 给 agent 用的入口 |
| `main.py` | 13 条“模型可能提议的命令”（正常、越界、注入、符号链接）逐条过一遍 |

## 怎么跑

需要 docker 和 `alpine:3.20`（命令里带了 `sudo`）。

```bash
sudo docker pull alpine:3.20
cd agent && uv sync
uv run python agent-sandbox/main.py           # 三道关 + 容器
uv run python agent-sandbox/main.py --check   # 只过三道关，不起容器
```

## 预期输出

2026-11-24，Ubuntu 22.04，Docker 27.3.1，alpine:3.20，Python 3.13：

```
正常：数一数 ERROR           ✓ 退出码 0，0.24s  → 1
越界：路径逃逸               ✕ 路径 /logs/../../etc/shadow 解析后是 /etc/shadow，不在 /logs 里
越界：永远不退出             ✕ tail 不允许选项 -f；允许的是 -n
注入：管道外传               ✕ 含有 shell 特殊字符 '|'：这里不经过 shell，一次只能跑一个命令
注入：换个删法               ✕ find 不在白名单里；能用的只有 grep, tail, head, wc, df
漏网：符号链接 -> /etc/shadow  ! 退出码 2，grep: /logs/shadow-link: Permission denied   ← 前三关放行，容器兜住
13 条里放行 4 条
```

## 练习

先猜结果，再运行。

1. `grep` 不带 `-m` 就拒（或自动补 `-m 50`），输出截断到 1500 字并注明总行数。
2. CASES 里加 `grep -c ERROR /logs-old/app.log`，确认第三关拦下；把判断改成 `startswith("/logs")` 再看。
3. 把 `run_command` 加进全景页的工具，问“磁盘满了吗”，看模型被拒时会不会按理由改写。
