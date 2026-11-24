# 并行工具调用

对应页面：[并行工具调用](https://study.ainiya.de/agent/parallel-tool-calls.html)

假模型一轮要 3 个只读工具，执行时各自模拟 0.8 / 1.5 / 0.4 秒延迟。比较串行、线程池、`asyncio.gather`、`max_workers=1`；`--fail` 让 jstack 超时，看失败的回执怎么写。不需要模型服务。

## 怎么跑

```bash
cd agent && uv sync
uv run python parallel-tool-calls/main.py
uv run python parallel-tool-calls/main.py --fail
```

## 预期输出

2026-11-24，2 核 ARM，Python 3.13：

```
串行 for 循环                    2.70s  回执 ['001', '002', '003']
线程池并发 (max_workers=3)        1.50s  回执 ['001', '002', '003']
asyncio.gather               1.50s  回执 ['001', '002', '003']
线程池并发 (max_workers=1)        2.70s  回执 ['001', '002', '003']

若每轮模型要 3s：分三轮要（每轮一个）≈ 11.7s；一轮要三个并发执行 ≈ 4.5s

--fail：
   toolu_fake_003  is_error TimeoutError: jstack 20 秒没返回（目标进程可能卡在安全点）……
```

## 练习

先猜结果，再运行。

1. 给每个工具标 `parallel_safe`，`heap_dump` 设 False；让假模型同一轮要 `heap_dump` 和 `thread_dump`，确认它们被串行执行。
2. `thread_dump` 延迟改成 30 秒，用 `submit` + `future.result(timeout=5)`，整批多久回来？
3. 把 Agent 循环页的列表推导换成线程池，跑一次真模型。
