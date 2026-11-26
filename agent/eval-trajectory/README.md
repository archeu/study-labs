# 不只看结果，也看过程（轨迹评测）

对应页面：[不只看结果，也看过程（轨迹评测）](https://study.ainiya.de/agent/eval-trajectory.html)

不重跑模型：读 [eval-dataset](../eval-dataset/README.md) 运行时存下的对话和调用记录。

| 命令 | 做什么 |
|---|---|
| `keys` | 每条用例的关键证据在哪个工具里：布置现场，各现场工具用宽松参数调一次，返回里有说中词的就是关键工具 |
| `judge <run>` | 逐条轨迹指标（第 1 轮查了什么、知识库是否先于有效现场、关键证据看到没有、重复调用、提议堆转储），和结果交叉成四格；存到 `runs/<run>.json` |
| `compare <a> <b>` | 两版的轨迹指标和四格移动 |
| `show <run> <用例>` | 看一条 |

## 怎么跑

```bash
cd agent
uv run python eval-trajectory/main.py keys
uv run python eval-trajectory/main.py judge base-@3-r1
uv run python eval-trajectory/main.py compare base-@3-r1 pr57-@4-r1
```

## 预期输出

2026-11-26/27，Qwen3-1.7B Q4_K_M（关思考，温度 0.8）的两次运行：

```
keys：关键证据在 search_logs 46 条、thread_dump 17 条、parse_gc_log 1 条；05（误报）现场里没有说中词
compare @3 → @4：第1轮看现场 50→4，第1轮调知识库 0→47，知识库先于有效现场 15→48，
                 看到关键证据 22→21，提议堆转储 23→1，有重复调用 0→7，通过 2→3
四格 @3 → @4：对且看到 2→3，没认出来 20→18，没查到 28→29，碰巧对 0→0
```

## 练习

1. 说中词不在现场里时（误报类），退回到“日志和 GC 都要看过”。
2. 记下每次 `search_logs` 的关键字，统计“没查到”那一格里最常见的几个。
3. 挑一条 @4 里“错，但看到了关键证据”的，用 tracing 的瀑布图指出关键证据在第几轮、结论在第几轮写偏。
