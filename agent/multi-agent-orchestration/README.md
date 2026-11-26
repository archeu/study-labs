# 多 agent：主从编排、并行子 agent、交接

对应页面：[多 agent：主从编排、并行子 agent、交接](https://study.ainiya.de/agent/multi-agent-orchestration.html)

| 文件 | 是什么 |
|---|---|
| `compare.py` | 同一个排障问题三种跑法：`single` 一个 agent 四个工具；`multi` 主 agent 写任务书 → 三个子 agent（create_agent）并行 → 主 agent 汇总；`multi-good` 任务书由人写好。用回调记账：每个 agent 调了几次模型、多少 token、最大一次输入多大 |
| `handoff.py` | 交接：节点返回 `Command(goto=…)` 把控制权和整段对话交给下一个 agent，不再收回。不调模型 |

工具和现场数据来自 `langgraph-basics/tools.py`（站 04 的 2026-11-08 20:16 那次）。

## 怎么跑

```bash
# 本机模型，见 langgraph-basics 的 README
cd agent && uv sync
uv run python multi-agent-orchestration/compare.py single
uv run python multi-agent-orchestration/compare.py multi
uv run python multi-agent-orchestration/compare.py multi-good
uv run python multi-agent-orchestration/handoff.py
```

## 预期输出

2026-11-26，2 核 ARM，llama.cpp b12618 + Qwen3-1.7B Q4_K_M（关掉思考，4 个槽位），langgraph 1.2.24，langchain 1.5.2。每种跑一次，小模型有随机性，数字只看量级。

| 跑法 | 模型调用 | 输入 token | 输出 token | 主 agent 最大一次输入 | 用时 | 结论里有导出接口 |
|---|---|---|---|---|---|---|
| single | 3 | 2325 | 177 | 856 | 34s | 否 |
| multi（模型写任务书） | 7 | 2286 | 520 | 394 | 96s | 否 |
| multi-good（人写任务书） | 7 | 3016 | 641 | 395 | 100s | 是 |

`multi` 里模型写的任务书：

```
任务书[logs]：检查订单接口日志，查找异常请求或错误信息
任务书[metrics]：查看系统指标，关注接口响应时间、QPS、错误率
任务书[notes]：记录告警时间点，核对日志与指标数据一致性
[metrics] 子 agent 读了工具原文 0 字，交回 10 字
```

没给时间窗，指标那份让查 QPS（它手里只有 GC 工具），于是一个工具都没调。汇总时主 agent 还把笔记里的建议（“先 jmap -histo 看谁最多”）写成了证据（“jmap -histo 显示有大量大对象”）。

`multi-good` 里日志子 agent 读了 763 字原文、交回 90 字，最终结论找到了 `ExportService`；但它把线程栈写成“卡在 OrderController /orders/{id}”，原文是 `ExportService.loadAll / writeExcel`。

本机只有 2 核，模型服务同一时刻算不动几个请求，所以并行省不了时间；连云端 API 时三个子 agent 才真的同时算。

`handoff.py`：

```
告警：order-04 P99 2.1s，GC 正常，orders 表在报警
  走过的节点： triage → jvm_agent → db_agent
    triage：这是 jvm_agent 的活，交给它，我不再管
    jvm_agent：看了前 1 条，GC 正常，像是数据库，再交给 db_agent
    db_agent：看了前 2 条，orders 表缺索引，结论由我直接回复值班人
```

## 练习

先猜结果，再运行。

1. 改 `triage` 的提示词，要求每份任务书都写“从几点开始查、用什么关键字、交回什么格式”，再跑 `multi`，指标子 agent 会调工具了吗？
2. 把 `SUB_RULE` 改成“交回 JSON：结论 + 引用的原文”，主 agent 还会把建议当证据吗？
3. 再加一个子 agent 专看线程栈（把 `thread_dump` 从日志 agent 拆出来），调用次数和 token 怎么变，结论有没有变好？
