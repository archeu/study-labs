# 全景：一个告警进来以后

对应页面：[全景：一个告警进来以后](https://study.ainiya.de/agent/pano-08-alert-to-ticket.html)

值班助手 v3 的骨架：主 agent 分诊 → 三个子 agent 并行调查 → 汇总 → 停下等人批准 → 带幂等键执行写操作 → 审计。用 LangGraph 写。

模型换成了照剧本出牌的函数（`MODEL_SECONDS` 模拟模型耗时），工具和现场数据来自站 04 的 `oncall.py`（2026-11-08 20:16 order-04 变慢）。**实测的是框架那一侧**：并行扇出、检查点、中断后换一个进程恢复、崩溃重放时写操作只做一次。

| 文件 | 是什么 |
|---|---|
| `main.py` | 状态图（triage → 3×investigate → summarize → approve → act）、SQLite 检查点、假工单系统（幂等键唯一）、审计日志 `data/audit.jsonl` |

## 怎么跑

```bash
cd agent && uv sync
P=pano-08-alert-to-ticket/main.py
uv run python $P run --flaky notes                                # notes 子 agent 第一次超时；跑到“等人批准”停下，进程退出
uv run python $P resume --approve create_ticket,notify --crash    # 另一个进程批准；建完工单后模拟崩溃
uv run python $P resume                                           # 崩溃后接着跑，看工单建了几张
uv run python $P history                                          # 列出这条线程的全部检查点
```

数据写在 `data/`（检查点、工单库、审计日志、每个节点执行了几次），不进仓库；`run` 会先清空它。

## 预期输出

2026-11-26，2 核 ARM，Python 3.13.18，langgraph 1.2.24，langgraph-checkpoint-sqlite 3.1.3：

```
  0.62s  triage     分诊：性能类，派 3 个子 agent
  0.63s  investigate[metrics] 开始（第 1 次执行）
  0.63s  investigate[logs] 开始（第 1 次执行）
  0.63s  investigate[notes] 开始（第 1 次执行）
  1.33s  investigate[notes] 超时，抛异常
  1.53s  investigate[metrics] 完成：原始 92 字 → 结论 92 字
  1.83s  investigate[logs] 完成：原始 1173 字 → 结论 83 字
  1.83s  ✗ notes 子 agent 超时；检查点里已存的 pending writes：['findings', 'branch:to:summarize', 'findings', 'branch:to:summarize', '__error__']。重试：graph.invoke(None)
  1.83s  investigate[notes] 开始（第 2 次执行）
  2.53s  investigate[notes] 完成：原始 210 字 → 结论 124 字
  3.33s  summarize  汇总 3 份结论，提议 3 个写操作
  3.34s  ⏸  停在 approve，等人批准：['create_ticket', 'notify', 'throttle_export']；进程退出
--- resume --approve create_ticket,notify --crash（新进程）
  0.01s  approve    值班 张三 20:31 批准了 ['create_ticket', 'notify']
  0.02s  act        create_ticket → 已建工单 INC-1108
  0.02s  act        建完工单、检查点还没存，进程崩了
--- resume（新进程）
  0.01s  act        create_ticket → 已建工单 INC-1108（幂等键已存在，没有重复执行）
  0.01s  act        notify → 已发 #order-oncall
  0.02s  线程 alert-7f3a：next=[]，检查点共 7 个
```

- 三个子 agent 同时开始（0.63s），这一步用了 1.2s，等于最慢的那个；串行要 1.2 + 0.9 + 0.7 = 2.8s。
- notes 超时后重试，只有 notes 重跑：`data/runs.json` 是 `{"metrics": 1, "logs": 1, "notes": 2, "act": 2}`。
- `act` 执行了两次，工单只有一张：靠的是幂等键 `alert-7f3a:create_ticket`，不是框架。

## 练习

先猜结果，再运行。

1. 去掉 `Tickets.do` 里查幂等键的那三行，再跑 `run` → `resume --crash` → `resume`，工单库里有几张？审计日志里能看出来吗？
2. 把 `approve` 和 `act` 合成一个节点（先 `interrupt()` 再建工单），批准后崩溃再恢复，`interrupt()` 那一行会再执行一次吗？返回什么？
3. `run --flaky notes,logs`：重试时哪几个子 agent 重跑？
