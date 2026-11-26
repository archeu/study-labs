# 状态图和检查点：长任务断点续跑

对应页面：[状态图和检查点：长任务断点续跑](https://study.ainiya.de/agent/langgraph-checkpoint.html)

不调模型。前两个脚本直接用全景那条链路（`pano-08-alert-to-ticket/main.py`）的检查点库。

| 文件 | 是什么 |
|---|---|
| `inspect_db.py` | 打开 SQLite 检查点库：两张表各存了什么，每个检查点多大，失败任务和中断请求挂在哪 |
| `fork.py` | 时间旅行：回到“三个子 agent 刚交回结论”的检查点，`update_state` 补一段人工结论，从那里分叉重跑 |
| `growth.py` | 跑 50 / 200 轮，每轮往 messages 加一条约 600 字节的消息：默认 reducer 和 DeltaChannel（1.2 的 beta）各占多少空间 |

## 怎么跑

```bash
cd agent && uv sync
uv run python pano-08-alert-to-ticket/main.py run --flaky notes   # 先生成全景的检查点库
uv run python langgraph-checkpoint/inspect_db.py
uv run python pano-08-alert-to-ticket/main.py run                 # fork 前重新跑一遍，停在等批准
uv run python langgraph-checkpoint/fork.py
uv run python langgraph-checkpoint/growth.py
```

## 预期输出

2026-11-26，2 核 ARM，Python 3.13.18，langgraph 1.2.24，langgraph-checkpoint-sqlite 3.1.3。

`inspect_db.py`（节选）：

```
checkpoints 表（一行 = 一个 superstep 结束时的快照）
  step -1  id …7141a2b0  parent …（无）          382 字节
  step  0  id …dcb30de4  parent …7141a2b0     605 字节
  step  1  id …433a6e35  parent …dcb30de4    1490 字节
  step  2  id …6379c52f  parent …433a6e35    2179 字节
  step  3  id …536e7ff0  parent …6379c52f    2856 字节

writes 表（一行 = 某个任务交出的一个键的改动）
  挂在 …dcb30de4  任务 …b23717  __pregel_tasks           136 字节      ← 三个 Send
  挂在 …433a6e35  任务 …fb24cb  __error__                 40 字节      ← notes 第一次超时
  挂在 …433a6e35  任务 …fb24cb  findings                 258 字节      ← notes 重试成功
  挂在 …536e7ff0  任务 …877478  __interrupt__            481 字节      ← 等批准
```

`fork.py`：

```
回到 step 2，那时 findings 有 3 段
update_state 生成新检查点 …7851cd54，next=['summarize']，从这里接着跑：
  0.03s  summarize  汇总 4 份结论，提议 3 个写操作
分叉后的历史（新的在前，parent 指回分叉点）：
  step  4  …272b019c  parent …7851cd54  source=loop   next=['approve']
  step  3  …7851cd54  parent …04719aec  source=update next=['summarize']
  step  3  …4eec359d  parent …04719aec  source=loop   next=['approve']      ← 原来那条还在
```

不写 `as_node` 时，新检查点的 `next` 是三个 `investigate`：框架把这次改动算到了 triage 头上，三个子 agent 会全部重跑。

`growth.py`：

```
reducer       轮数    检查点行   checkpoints 表      最新一行   writes 表      跑完   读回 state
默认            50      52          705 KB   27.0 KB      27 KB   0.40s     0.5 ms
Delta         50      52           30 KB    0.6 KB      27 KB   0.43s     1.3 ms
默认           200     202        10763 KB  106.5 KB     106 KB   1.45s     0.6 ms
Delta        200     202          277 KB    0.6 KB     106 KB   1.27s     3.1 ms
```

默认 reducer 每个检查点都存整份列表，总量随轮数平方增长；DeltaChannel 只存改动，读回时要重放，所以读得慢一点。

## 练习

先猜结果，再运行。

1. 在 `fork.py` 里去掉 `as_node="investigate"`，`invoke(None, new_cfg)` 会先跑哪个节点？
2. `growth.py` 跑 400 轮，默认 reducer 的 checkpoints 表大约多大？（提示：平方）
3. 把 `DeltaChannel(append)` 换成 `DeltaChannel(append, snapshot_frequency=10)`，checkpoints 表和读回时间怎么变？
